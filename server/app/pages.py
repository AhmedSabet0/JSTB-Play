"""Public pages: home, privacy policy and account deletion (Google Play asks for the last two as links)."""
import html
import os

APP_NAME = os.environ.get("APP_NAME", "JSTB")
OPERATOR = os.environ.get("OPERATOR_NAME", "") or APP_NAME
CONTACT = os.environ.get("CONTACT_EMAIL", "")
POLICY_DATE = os.environ.get("POLICY_DATE", "November 3, 2026")

e = html.escape

STYLE = """
:root{color-scheme:light dark;--paper:#eff3f8;--surface:#fff;--ink:#15233f;--ink-2:#4a5874;--line:#dce3ed;--action:#0a66c2;
--action-ink:#fff;--error:#c2362b;--good:#11805b}
@media (prefers-color-scheme:dark){:root{--paper:#0e1522;--surface:#162033;--ink:#e7edf6;--ink-2:#9aa8bf;--line:#26344d;
--action:#5aa2ee;--action-ink:#08131f;--error:#f27d72;--good:#4cc795}}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);
font:16px/1.6 system-ui,-apple-system,Roboto,"Segoe UI",Arial,sans-serif}
main{max-width:720px;margin:0 auto;padding:32px 16px 64px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:24px 22px;margin-top:18px}
h1{font-size:28px;line-height:1.2;margin:0 0 6px}h2{font-size:18px;margin:26px 0 6px}p,li{color:var(--ink)}
.muted{color:var(--ink-2)}a{color:var(--action)}ul{padding-left:20px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700}
.brand svg{width:32px;height:32px}.brand rect{fill:var(--action)}.brand path{fill:var(--action-ink)}.brand .arr{fill:none;stroke:var(--action-ink);stroke-width:3.4;stroke-linecap:round;stroke-linejoin:round}
label{display:block;font-weight:600;margin:14px 0 6px}
input{width:100%;font:inherit;padding:12px 14px;border:1px solid var(--line);border-radius:12px;background:var(--paper);color:var(--ink)}
button{margin-top:18px;font:inherit;font-weight:700;padding:12px 20px;border:0;border-radius:12px;background:var(--error);color:#fff;cursor:pointer}
.ok{color:var(--good);font-weight:700}.bad{color:var(--error);font-weight:700}
.links{display:flex;gap:18px;flex-wrap:wrap;margin-top:18px}
.btn{display:inline-block;margin-top:18px;font-weight:700;padding:12px 20px;border-radius:12px;background:var(--action);
color:var(--action-ink);text-decoration:none}
"""

LOGO = ('<svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="16"/>'
        '<path d="M10.675357142857145 36.12714285714286Q9.2375 36.12714285714286 8.153214285714288 35.63214285714285Q7.0689285714285734 35.137142857142855 6.350000000000001 34.371071428571426Q5.63107142857143 33.605 5.277500000000002 32.78Q4.923928571428573 31.955 4.900357142857144 31.271428571428572L8.459642857142859 30.11642857142857Q8.742500000000001 31.247857142857143 9.308214285714287 31.81357142857143Q9.873928571428573 32.379285714285714 10.722500000000002 32.379285714285714Q11.358928571428574 32.379285714285714 11.865714285714288 32.096428571428575Q12.372500000000002 31.81357142857143 12.655357142857145 31.236071428571428Q12.938214285714288 30.658571428571427 12.938214285714288 29.762857142857143V19.25H17.01607142857143V30.823571428571427Q17.01607142857143 31.978571428571428 16.580000000000002 32.96857142857143Q16.143928571428575 33.95857142857143 15.318928571428575 34.65392857142857Q14.493928571428574 35.34928571428571 13.315357142857145 35.738214285714285Q12.136785714285717 36.12714285714286 10.675357142857145 36.12714285714286ZM25.001785714285713 36.03285714285714Q23.823214285714286 36.03285714285714 22.72714285714286 35.714642857142856Q21.631071428571428 35.39642857142857 20.73535714285714 34.8425Q19.839642857142856 34.28857142857143 19.262142857142855 33.56964285714285Q18.684642857142855 32.85071428571428 18.543214285714285 32.072857142857146L22.5975 30.917857142857144Q22.833214285714284 31.57785714285714 23.4225 32.02571428571429Q24.011785714285715 32.473571428571425 24.9075 32.520714285714284Q25.7325 32.544285714285714 26.298214285714288 32.12Q26.863928571428573 31.695714285714285 26.863928571428573 31.012142857142855Q26.863928571428573 30.517142857142858 26.49857142857143 30.104642857142856Q26.133214285714285 29.692142857142855 25.37892857142857 29.503571428571426L22.974642857142857 28.890714285714285Q21.725357142857142 28.560714285714283 20.817857142857143 27.900714285714283Q19.91035714285714 27.240714285714283 19.41535714285714 26.2625Q18.920357142857142 25.284285714285716 18.920357142857142 24.058571428571426Q18.920357142857142 21.654285714285713 20.51142857142857 20.310714285714283Q22.1025 18.967142857142857 25.001785714285713 18.967142857142857Q26.581071428571427 18.967142857142857 27.771428571428572 19.379642857142855Q28.961785714285714 19.792142857142856 29.798571428571428 20.6525Q30.635357142857142 21.512857142857143 31.177500000000002 22.856428571428573L27.146785714285713 24.034999999999997Q27.005357142857143 23.469285714285714 26.451428571428572 22.974285714285713Q25.8975 22.479285714285716 24.978214285714287 22.479285714285716Q24.10607142857143 22.479285714285716 23.611071428571428 22.880000000000003Q23.116071428571427 23.280714285714286 23.116071428571427 24.034999999999997Q23.116071428571427 24.553571428571427 23.505 24.90714285714286Q23.89392857142857 25.260714285714286 24.624642857142856 25.425714285714285L27.052500000000002 26.03857142857143Q28.985357142857143 26.533571428571427 30.06964285714286 27.900714285714287Q31.153928571428573 29.267857142857142 31.153928571428573 31.012142857142855Q31.153928571428573 32.59142857142857 30.423214285714288 33.722857142857144Q29.692500000000003 34.854285714285716 28.31357142857143 35.44357142857143Q26.934642857142855 36.03285714285714 25.001785714285713 36.03285714285714ZM36.59392857142857 35.75V22.997857142857143H31.85607142857143V19.25H45.38607142857143V22.997857142857143H40.67178571428572V35.75ZM46.79535714285714 35.75V19.25H52.64107142857143Q54.361785714285716 19.25 55.67 19.768571428571427Q56.97821428571429 20.287142857142854 57.72071428571429 21.359642857142855Q58.46321428571429 22.432142857142857 58.46321428571429 24.082142857142856Q58.46321428571429 24.88357142857143 58.027142857142856 25.637857142857143Q57.591071428571425 26.392142857142858 56.81321428571429 27.005Q57.8975 27.594285714285714 58.498571428571424 28.54892857142857Q59.099642857142854 29.503571428571426 59.099642857142854 30.894285714285715Q59.099642857142854 32.45 58.380714285714284 33.534285714285716Q57.66178571428571 34.61857142857143 56.38892857142857 35.184285714285714Q55.11607142857143 35.75 53.466071428571425 35.75ZM50.75535714285714 32.14357142857143H52.99464285714286Q53.86678571428571 32.14357142857143 54.373571428571424 31.68392857142857Q54.88035714285714 31.224285714285713 54.88035714285714 30.49357142857143Q54.88035714285714 29.739285714285714 54.349999999999994 29.303214285714283Q53.81964285714285 28.867142857142856 52.92392857142857 28.867142857142856H50.75535714285714ZM50.75535714285714 25.614285714285714H52.42892857142857Q53.253928571428574 25.614285714285714 53.7725 25.189999999999998Q54.29107142857143 24.765714285714285 54.29107142857143 24.01142857142857Q54.29107142857143 23.257142857142856 53.760714285714286 22.84464285714286Q53.230357142857144 22.432142857142857 52.38178571428571 22.432142857142857H50.75535714285714Z"/><path class="arr" d="M6.4,45.5 L57.6,45.5 M53.2,41.1 L57.6,45.5 L53.2,49.9"/></svg>')


def page(title, body):
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{e(title)}</title><style>{STYLE}</style></head>
<body><main><div class="brand">{LOGO}<span>{e(APP_NAME)}</span></div>{body}</main></body></html>"""


def contact_line():
    return f'<a href="mailto:{e(CONTACT)}">{e(CONTACT)}</a>' if CONTACT else "the contact address on the app's Google Play page"


def home():
    return page(APP_NAME, f"""<div class="card"><h1>{e(APP_NAME)}</h1>
<p class="muted">An Android app that helps you apply to jobs that fit your CV: it keeps your answers ready, fills
application forms with 1-tap confirmation, and tracks every application. Your CV, answers and jobs are the same on every phone you sign in on, and in your Google Drive if you connect it.</p>
<p class="muted">{e(APP_NAME)} is not affiliated with, endorsed by or sponsored by LinkedIn.</p>
<div class="links"><a href="/privacy">Privacy policy</a><a href="/delete-account">Delete your account</a></div></div>""")


def privacy():
    return page(f"Privacy policy - {APP_NAME}", f"""<div class="card"><h1>Privacy policy</h1>
<p class="muted">{e(APP_NAME)} &middot; last updated {e(POLICY_DATE)}</p>
<p>This policy explains what the {e(APP_NAME)} Android app and its server collect, why, and what you can do about it.
The service is run by {e(OPERATOR)}. Questions: {contact_line()}.</p>

<h2>What stays on your phone only</h2>
<ul><li>Your LinkedIn sign-in. You sign in on LinkedIn's own page inside the app. We never receive your LinkedIn password,
and the LinkedIn session (cookies) stays on your phone.</li>
<li>The app's update downloads, its pacing state and its debug files.</li></ul>

<h2>What our server keeps</h2>
<ul><li><b>Your account:</b> name and email address, and either a password (stored only as a salted scrypt hash) or,
when you continue with Google, your Google account ID.</li>
<li><b>Your data, so it is the same on every phone you sign in on:</b> your CV file and its text, the answers you save
(the setup answers and "Your info"), your settings and job titles, the notes for the AI, the jobs you saved to apply to
and the jobs the app handled, with their stage and notes. Your phone keeps its own copy too.</li>
<li><b>Activity the app reports:</b> whether auto apply is running, its status, the app version and phone model, and for
each job it applied to (or could not finish): the LinkedIn job ID, job title, company name, result, match score and time.
This lets us show you and the service admin how many applications were sent, and lets the admin stop auto apply.</li>
<li><b>The app's activity log:</b> the steps the app takes (searches, the jobs it looked at and why it skipped or applied,
the answers it filled in, errors), the same lines you see under Activity in the app. The admin uses it to help you and
to find problems. The newest lines are kept; older ones are deleted automatically.</li>
<li><b>Jobs you track in the app:</b> when you mark a job as applied, or change its stage (interview, offer...), the job
title, company, link and stage.</li>
<li><b>AI usage:</b> how many AI requests your account made each day and how many tokens they used, to enforce daily
limits.</li></ul>
<p>The server does not keep access logs of your requests. Your IP address is only held in memory for a short time to
limit repeated sign-in attempts.</p>

<h2>What passes through our server to the AI</h2>
<p>To pick matching jobs and fill in application forms, the app sends the AI the text of your CV, your saved answers and
notes, the job description and the form's questions. These go through our server to OpenRouter (openrouter.ai), which
forwards them to the AI model provider that produces the answer. Our server does not store this content. OpenRouter's
privacy policy and the model provider's terms apply to it.</p>

<h2>Sign in with Google and Google Drive</h2>
<p>When you continue with Google, Google gives our server your name, email address and Google account ID; they make
your account and sign you in. The same consent connects Google Drive. An account made earlier with an email and a
password can connect Drive in the app (Settings &rarr; Your data).</p>
<p>With Google Drive connected, our server makes a folder named "JSTB" in your Drive with a copy of
your CV, a "My answers" sheet and a "My jobs" sheet, keeps them up to date, and reads the changes you make to those
files so they come back to the app. It uses Google's <i>drive.file</i> permission: it can see and change only the files
it made (or that you open with it), not the rest of your Drive. It also reads your Google account's email address to
show which account is connected. The Google sign-in tokens are stored encrypted on our server and are used for nothing
else.</p>
<p>You can disconnect at any time in the app (Settings &rarr; Google Drive &rarr; Disconnect) or at
<a href="https://myaccount.google.com/permissions">myaccount.google.com/permissions</a>. The files stay in your Drive;
they are yours to keep or delete.</p>
<p>{e(APP_NAME)}'s use and transfer of information received from Google APIs adheres to the
<a href="https://developers.google.com/terms/api-services-user-data-policy">Google API Services User Data Policy</a>,
including the Limited Use requirements.</p>

<h2>What we do not do</h2>
<ul><li>We do not sell your data or share it with advertisers.</li><li>The app has no ads and no third-party analytics.
</li><li>We share data only with OpenRouter and with your own Google Drive (when you connect it), as described above, or
when the law requires it.</li></ul>

<h2>How long we keep it</h2>
<p>Your account, your data, activity and usage records are kept while your account exists. When you delete your
account they are deleted from the server right away. Deleting a fact, a job or your CV in the app deletes it from the
server too.</p>

<h2>Deleting your account</h2>
<p>In the app: Settings &rarr; Account &rarr; Delete account. Or on the web: <a href="/delete-account">delete your
account</a>. Both delete your account, your CV, answers, settings and jobs kept on the server, your reported activity
and your AI usage records, and disconnect Google Drive (the files in your Drive stay yours). If you can no longer sign in,
email {contact_line()} from your account's email address and we will delete it within 7 days.</p>

<h2>Security</h2>
<p>The app talks to the server over HTTPS only. Passwords are hashed, and sign-in tokens are stored only as hashes.</p>

<h2>Children</h2>
<p>{e(APP_NAME)} is meant for job seekers aged 18 or over and is not directed to children.</p>

<h2>LinkedIn</h2>
<p>{e(APP_NAME)} is not affiliated with LinkedIn. You use your own LinkedIn account and are responsible for following
LinkedIn's terms.</p>

<h2>Changes</h2>
<p>If this policy changes, the new version is posted on this page with a new date.</p></div>""")


def delete_form(message="", ok=False):
    note = ""
    if message:
        note = f'<p class="{"ok" if ok else "bad"}">{e(message)}</p>'
    if ok:
        return page(f"Account deleted - {APP_NAME}", f'<div class="card"><h1>Account deleted</h1>{note}'
                    f'<p class="muted">Data on your phone is removed when you uninstall the app.</p></div>')
    return page(f"Delete your account - {APP_NAME}", f"""<div class="card"><h1>Delete your {e(APP_NAME)} account</h1>
<p class="muted">This deletes your account and everything our server keeps for it, right away: your CV, answers,
settings and jobs, the activity the app reported and your AI usage records. It also disconnects Google Drive (the files
in your Drive stay yours). It cannot be undone. The copy on your phone (and your LinkedIn sign-in) is removed when you
uninstall the app.</p>
{note}
<form method="post" action="/delete-account" autocomplete="off">
<label for="email">Email of your account</label><input id="email" name="email" type="email" required>
<label for="password">Password</label><input id="password" name="password" type="password" required>
<button type="submit">Delete my account</button></form>
<p>Made your account with Google? <a href="/delete-account/google">Delete it by signing in with Google</a>.</p>
<p class="muted">Forgot your password? Email {contact_line()} from your account's email address and we will delete the
account within 7 days.</p></div>""")


def drive_result(ok, text):
    """The page Google sends the browser back to, after connecting Google Drive."""
    back = '<a class="btn" href="autoapply://drive">Back to JSTB</a>'
    if ok:
        who = f" as <b>{e(text)}</b>" if text else ""
        return page(f"Google Drive connected - {APP_NAME}", f"""<div class="card"><h1>Google Drive is connected</h1>
<p class="ok">Connected{who}.</p>
<p>Your <b>JSTB</b> folder is being made in your Drive: your CV, <b>My answers</b> and <b>My jobs</b>. What you change
there comes back to the app.</p><p class="muted">You can close this page and go back to the app.</p>{back}</div>""")
    return page(f"Google Drive not connected - {APP_NAME}", f"""<div class="card"><h1>Google Drive is not connected</h1>
<p class="bad">{e(text)}</p><p class="muted">Go back to the app and try again.</p>{back}</div>""")


def signed_in(email, code):
    """After Sign in with Google: back to the app with a one-time code (worthless without the app's own verifier)."""
    link = f"autoapply://auth?code={e(code)}"
    return page(f"Signed in - {APP_NAME}", f"""<div class="card"><h1>You are signed in</h1>
<p class="ok">Signed in with Google as <b>{e(email)}</b>.</p>
<p>Your <b>JSTB</b> folder in this Google Drive keeps your CV, answers and jobs. Go back to the app to continue.</p>
<a class="btn" id="back" href="{link}">Back to JSTB</a>
<p class="muted">If the app does not open, open it yourself: it finishes signing in by itself.</p></div>
<script>setTimeout(function () {{ location.href = "{link}"; }}, 300);</script>""")


def google_linked(email):
    return page(f"Google added - {APP_NAME}", f"""<div class="card"><h1>Google is added to your account</h1>
<p class="ok">From now on you can sign in with Google as <b>{e(email)}</b>, on any phone.</p>
<p>Your <b>JSTB</b> folder in this Google Drive keeps your CV, answers and jobs.</p>
<a class="btn" href="autoapply://drive">Back to JSTB</a></div>
<script>setTimeout(function () {{ location.href = "autoapply://drive"; }}, 300);</script>""")


def signin_failed(message):
    return page(f"Not signed in - {APP_NAME}", f"""<div class="card"><h1>You are not signed in</h1>
<p class="bad">{e(message)}</p><p class="muted">Go back to the app and try again.</p>
<a class="btn" href="autoapply://auth">Back to JSTB</a></div>""")


def delete_confirm(email, token):
    return page(f"Delete your account - {APP_NAME}", f"""<div class="card"><h1>Delete your {e(APP_NAME)} account</h1>
<p>Signed in with Google as <b>{e(email)}</b>.</p>
<p class="muted">This deletes your account and everything our server keeps for it, right away: your CV, answers and jobs,
the activity the app reported and your AI usage records. It also disconnects Google Drive (the files in your Drive stay
yours). It cannot be undone.</p>
<form method="post" action="/delete-account/google"><input type="hidden" name="token" value="{e(token)}">
<button type="submit">Delete my account</button></form></div>""")
