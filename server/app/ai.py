"""The AI proxy: the app sends the conversation, the server adds the OpenRouter key and the model set in the admin page."""
import os

import httpx

OPENROUTER_URL = os.environ.get("OPENROUTER_URL", "https://openrouter.ai/api/v1/chat/completions")
PUBLIC_URL = os.environ.get("PUBLIC_URL", "")
APP_NAME = os.environ.get("APP_NAME", "JSTB")

MAX_CHARS = 60000      # the whole conversation, per request
MAX_TOKENS = 2000

_client = None


def client():
    global _client
    if _client is None:
        _client = httpx.AsyncClient(timeout=httpx.Timeout(90.0, connect=20.0))
    return _client


async def close():
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


def clean_messages(raw):
    """[{role, content}] with text content only, or None when the request is not usable."""
    if not isinstance(raw, list) or not raw or len(raw) > 40:
        return None
    out, total = [], 0
    for m in raw:
        if not isinstance(m, dict) or m.get("role") not in ("system", "user", "assistant"):
            return None
        content = m.get("content")
        if not isinstance(content, str):
            return None
        total += len(content)
        out.append({"role": m["role"], "content": content})
    return out if total <= MAX_CHARS else None


class Upstream(Exception):
    """OpenRouter answered with an error. `status` is what the app gets; `fatal` errors are not worth retrying."""

    def __init__(self, status, message, code):
        super().__init__(message)
        self.status = status
        self.message = message
        self.code = code


def _upstream_message(resp):
    try:
        data = resp.json()
        err = data.get("error") if isinstance(data, dict) else None
        if isinstance(err, dict) and err.get("message"):
            return str(err["message"])[:300]
    except ValueError:
        pass
    return resp.text[:300]


async def chat(key, model, messages, max_tokens, temperature):
    """The OpenRouter reply (a dict with choices and usage), or raises Upstream."""
    payload = {"model": model, "messages": messages, "max_tokens": max_tokens, "temperature": temperature}
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json", "X-Title": APP_NAME}
    if PUBLIC_URL:
        headers["HTTP-Referer"] = PUBLIC_URL
    try:
        resp = await client().post(OPENROUTER_URL, json=payload, headers=headers)
    except httpx.HTTPError as e:
        raise Upstream(502, f"Could not reach the AI provider ({type(e).__name__}). Try again.", "upstream_unreachable")
    if resp.status_code == 200:
        try:
            data = resp.json()
        except ValueError:
            raise Upstream(502, "The AI provider sent an unreadable answer. Try again.", "upstream_invalid")
        err = data.get("error") if isinstance(data, dict) else None
        if isinstance(err, dict):
            code = err.get("code")
            if code in (401, 402, 403):
                raise Upstream(403, "The AI service is not available right now. (Admin: check the OpenRouter key and "
                                    "its credits.)", "ai_unavailable")
            raise Upstream(502, "The AI provider failed: " + str(err.get("message", ""))[:200], "upstream_error")
        if not isinstance(data, dict) or not isinstance(data.get("choices"), list):
            raise Upstream(502, "The AI provider sent an unreadable answer. Try again.", "upstream_invalid")
        return data
    msg = _upstream_message(resp)
    if resp.status_code in (401, 402, 403):
        raise Upstream(403, "The AI service is not available right now. (Admin: check the OpenRouter key and its "
                            "credits.)", "ai_unavailable")
    if resp.status_code == 404 or (resp.status_code == 400 and "model" in msg.lower()):
        raise Upstream(403, "The AI model set by the admin is not available. (Admin: check the model name.)",
                       "bad_model")
    if resp.status_code == 429:
        raise Upstream(429, "The AI provider is busy. Trying again shortly.", "upstream_rate_limit")
    if resp.status_code == 400:
        raise Upstream(400, "The AI provider refused this request: " + msg[:200], "upstream_bad_request")
    raise Upstream(502, f"The AI provider failed (HTTP {resp.status_code}). Try again.", "upstream_error")
