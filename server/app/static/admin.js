/* JSTB admin page: users, Pro, applications, Stop, AI settings. */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = (k, v) => {
    try {
      if (v === undefined) return localStorage.getItem(k);
      localStorage.setItem(k, v);
    } catch (e) { /* storage unavailable */ }
    return null;
  };

  class SignedOut extends Error {}

  async function api(path, body) {
    const opts = { credentials: 'same-origin', headers: { 'X-Requested-With': 'admin' } };
    if (body !== undefined) {
      opts.method = 'POST';
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(path, opts);
    } catch (e) {
      throw new Error('Could not reach the server.');
    }
    let data = {};
    try { data = await res.json(); } catch (e) { /* not json */ }
    if (res.status === 401 && path !== '/admin/api/login') throw new SignedOut('Signed out');
    if (!res.ok || data.ok === false) {
      const err = data.error;
      throw new Error((err && err.message) || (typeof err === 'string' ? err : `Request failed (${res.status})`));
    }
    return data;
  }

  let toastTimer = null;
  function toast(msg, bad = false) {
    const t = $('toast');
    t.textContent = msg;
    t.className = `toast${bad ? ' bad' : ''}`;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 6000 : 3000);
  }
  function fail(e) {
    if (e instanceof SignedOut) { showLogin(); return; }
    toast(e.message, true);
  }

  const fmtNum = (n) => Number(n || 0).toLocaleString();
  function ago(ts) {
    if (!ts) return 'never';
    const s = Math.max(0, Date.now() / 1000 - ts);
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.floor(s / 60)} min ago`;
    if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
    if (s < 86400 * 7) return `${Math.floor(s / 86400)} d ago`;
    return new Date(ts * 1000).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
  }
  const when = (ts) => new Date(ts * 1000).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const STATUS = {
    applied: ['Applied', 'good'], error: ['Error', 'bad'], stuck: ['Stuck', 'warn'], needs_answer: ['Needs an answer', 'warn'],
  };

  // ------------------------------------------------------------------ sign in
  function showLogin() {
    stopPolling();
    $('v-app').hidden = true;
    $('drawer').hidden = true;
    $('v-login').hidden = false;
    setTimeout(() => $('login-pw').focus(), 50);
  }
  $('login-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    $('login-err').hidden = true;
    try {
      await api('/admin/api/login', { password: $('login-pw').value });
      $('login-pw').value = '';
      await boot();
    } catch (e) {
      $('login-err').textContent = e.message;
      $('login-err').hidden = false;
    }
  });
  $('btn-logout').addEventListener('click', async () => {
    try { await api('/admin/api/logout', {}); } catch (e) { /* signed out anyway */ }
    showLogin();
  });

  // ------------------------------------------------------------------ tabs
  function showTab(name) {
    document.querySelectorAll('.tab').forEach((t) => {
      t.classList.toggle('on', t.dataset.tab === name);
      t.setAttribute('aria-selected', t.dataset.tab === name ? 'true' : 'false');
    });
    document.querySelectorAll('.tabpane').forEach((p) => { p.hidden = p.id !== `t-${name}`; });
    store('aa-admin-tab', name);
    if (name === 'settings') fillSettings();
    if (name === 'activity') { fillUserPick(); pollFeed(true); }
  }
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));

  // ------------------------------------------------------------------ overview
  let data = null;
  let filter = 'all';
  let pollTimer = null;

  async function boot() {
    try {
      data = await api('/admin/api/overview');
    } catch (e) {
      if (e instanceof SignedOut) { showLogin(); return; }
      toast(e.message, true);
      return;
    }
    $('v-login').hidden = true;
    $('v-app').hidden = false;
    $('l-privacy').textContent = `${location.origin}/privacy`;
    $('l-delete').textContent = `${location.origin}/delete-account`;
    const tab = store('aa-admin-tab');
    showTab(['settings', 'activity'].includes(tab) ? tab : 'users');
    render();
    startPolling();
  }

  function startPolling() {
    stopPolling();
    pollTimer = setInterval(() => { if (!document.hidden) refresh(); }, 5000);
  }
  function stopPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  async function refresh() {
    try {
      data = await api('/admin/api/overview');
      render();
      if (!$('drawer').hidden && openId) loadUser(openId, true);
      if (!$('t-activity').hidden) fillUserPick();
    } catch (e) {
      if (e instanceof SignedOut) showLogin();
    }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && pollTimer) refresh(); });

  function render() {
    const t = data.totals;
    $('k-users').textContent = fmtNum(t.users);
    $('k-pro').textContent = `${fmtNum(t.pro)} with Pro`;
    $('k-running').textContent = fmtNum(t.running);
    $('k-today').textContent = fmtNum(t.applied_today);
    $('k-week').textContent = fmtNum(t.applied_week);
    $('k-total').textContent = fmtNum(t.applied_total);
    $('k-ai').textContent = fmtNum(t.ai_today);
    $('k-tokens').textContent = t.tokens_today ? `${fmtNum(t.tokens_today)} tokens` : '';
    $('btn-stop-all').disabled = !t.running;

    const warn = $('setup-warn');
    warn.hidden = data.settings.has_key;
    if (!data.settings.has_key && !warn.dataset.done) {
      warn.dataset.done = '1';
      warn.innerHTML = '<span><b>Add the OpenRouter key.</b> Until then the app cannot use the AI, so nobody can apply.</span>'
        + '<button class="btn" type="button" id="warn-go">Open Settings</button>';
      $('warn-go').addEventListener('click', () => showTab('settings'));
    }
    renderUsers();
  }

  function stateCell(u) {
    if (u.state === 'running') {
      const label = u.mode === 'test' ? 'Test run' : u.mode === 'dry' ? 'Dry run' : 'Applying';
      return `<span class="st"><i class="dot run"></i>${label}${u.stop_pending ? ' · stopping' : ''}</span>`
        + `<span class="msg" title="${esc(u.status)}">${esc(u.status || 'Working')}</span>`;
    }
    if (u.state === 'lost') {
      return `<span class="st"><i class="dot lost"></i>No contact</span><span class="msg">Was applying, last seen ${ago(u.last_seen)}</span>`;
    }
    const seen = u.last_seen ? ` · ${ago(u.last_seen)}` : '';
    const msg = u.last_event || (u.last_seen ? 'No activity reported' : 'Has not opened the app yet');
    return `<span class="st"><i class="dot"></i>Idle${seen}</span><span class="msg" title="${esc(msg)}">${esc(msg)}</span>`;
  }

  function visibleUsers() {
    const q = $('q').value.trim().toLowerCase();
    return data.users.filter((u) => {
      if (filter === 'running' && !u.online) return false;
      if (filter === 'pro' && !u.pro) return false;
      if (filter === 'free' && u.pro) return false;
      return !q || u.email.includes(q) || (u.name || '').toLowerCase().includes(q);
    });
  }

  function renderUsers() {
    const list = visibleUsers();
    const box = $('users');
    const sig = JSON.stringify(list.map((u) => [u.id, u.pro, u.state, u.status, u.applied_today, u.applied_week,
      u.applied_total, u.ai_today, u.stop_pending, Math.floor((Date.now() / 1000 - (u.last_seen || 0)) / 60)]));
    if (box.dataset.sig === sig) return;
    box.dataset.sig = sig;
    $('users-table').hidden = !list.length;
    $('users-empty').hidden = !!list.length;
    $('users-empty').textContent = data.users.length ? 'No user matches.' : 'No users yet. They show up here when they create an account in the app.';
    box.innerHTML = list.map((u) => `
      <div class="urow" data-id="${u.id}">
        <div class="who"><b>${esc(u.name || u.email.split('@')[0])}</b><small>${esc(u.email)}</small></div>
        <div class="now">${stateCell(u)}</div>
        <div class="num"><span class="mlabel">Today</span><span class="big">${fmtNum(u.applied_today)}</span></div>
        <div class="num"><span class="mlabel">7 days</span>${fmtNum(u.applied_week)}</div>
        <div class="num"><span class="mlabel">Total</span>${fmtNum(u.applied_total)}</div>
        <div class="num"><span class="mlabel">AI today</span>${fmtNum(u.ai_today)}</div>
        <div class="pro-cell"><label class="switch-row" title="Pro"><input type="checkbox" data-pro data-id="${u.id}" ${u.pro ? 'checked' : ''}
          aria-label="Pro for ${esc(u.email)}"><span class="switch" aria-hidden="true"></span></label></div>
        <div class="acts">
          <button class="btn small stop" data-stop="${u.id}" type="button" ${u.online && !u.stop_pending ? '' : 'disabled'}>${u.stop_pending ? 'Stopping…' : 'Stop'}</button>
          <button class="btn small" data-open="${u.id}" type="button">Details</button>
          <button class="btn small danger" data-del="${u.id}" type="button" aria-label="Delete ${esc(u.email)}">Delete</button>
        </div>
      </div>`).join('');
    box.querySelectorAll('[data-pro]').forEach((el) => el.addEventListener('change', () => setPro(Number(el.dataset.id), el.checked, el)));
    box.querySelectorAll('[data-stop]').forEach((el) => el.addEventListener('click', () => stopUser(Number(el.dataset.stop), el)));
    box.querySelectorAll('[data-open]').forEach((el) => el.addEventListener('click', () => openUser(Number(el.dataset.open))));
    box.querySelectorAll('[data-del]').forEach((el) => el.addEventListener('click', () => deleteUser(Number(el.dataset.del), el)));
  }

  // Delete from the list: a second tap within 5 seconds confirms.
  async function deleteUser(id, btn) {
    const u = data.users.find((x) => x.id === id) || {};
    if (btn.dataset.sure !== '1') {
      btn.dataset.sure = '1';
      btn.textContent = 'Sure? Tap again';
      setTimeout(() => { if (btn.isConnected) { btn.dataset.sure = ''; btn.textContent = 'Delete'; } }, 5000);
      return;
    }
    btn.disabled = true;
    try {
      await api(`/admin/api/users/${id}/delete`, {});
      toast(`Deleted ${u.email || 'the user'}.`);
      $('users').dataset.sig = '';
      refresh();
    } catch (e) { btn.disabled = false; fail(e); }
  }

  $('q').addEventListener('input', () => { $('users').dataset.sig = ''; renderUsers(); });
  document.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => {
    filter = c.dataset.filter;
    document.querySelectorAll('.chip').forEach((x) => x.classList.toggle('on', x === c));
    $('users').dataset.sig = '';
    renderUsers();
  }));

  function userById(id) { return data.users.find((u) => u.id === id); }

  async function setPro(id, on, el) {
    const u = userById(id);
    if (el) el.disabled = true;
    try {
      await api(`/admin/api/users/${id}`, { pro: on });
      toast(on ? `Pro is on for ${u ? u.email : 'this user'}. They can start auto apply now.`
        : `Pro is off for ${u ? u.email : 'this user'}.${u && u.online ? ' Their run stops within 30 seconds.' : ''}`);
    } catch (e) {
      if (el) el.checked = !on;
      fail(e);
    } finally {
      if (el) el.disabled = false;
    }
    await refresh();
  }

  async function stopUser(id, el) {
    const u = userById(id);
    if (el) el.disabled = true;
    try {
      const r = await api(`/admin/api/users/${id}/stop`, {});
      toast(r.online ? `Stopping ${u ? u.email : 'the run'}: it stops within 30 seconds.` : 'Not applying right now; nothing to stop.');
    } catch (e) { fail(e); }
    await refresh();
  }

  $('btn-stop-all').addEventListener('click', async () => {
    const n = data ? data.totals.running : 0;
    // eslint-disable-next-line no-alert
    if (!window.confirm(`Stop auto apply for everyone who is applying now (${n})? They can start again; turn Pro off to keep someone stopped.`)) return;
    try {
      const r = await api('/admin/api/stop-all', {});
      toast(`Stopping ${r.stopped} run(s). They stop within 30 seconds.`);
    } catch (e) { fail(e); }
    refresh();
  });

  // ------------------------------------------------------------------ live activity
  const lineTone = (t) => (/APPLIED|^\s*\+ |Marked applied|Applied to/.test(t) ? 'ok'
    : /Error|error|failed|Could not|Stopped after/.test(t) ? 'bad' : /Question for you|Waiting for your answer/.test(t) ? 'q' : '');
  const clock = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
  function lineHtml(e, withWho) {
    return `<li class="${lineTone(e.text)}${withWho ? '' : ' one'}"><span class="t">${clock(e.at)}</span>${withWho ? `<span class="who">${esc(e.email)}</span>` : ''}`
      + `<span class="x">${esc(e.text)}</span></li>`;
  }
  let feedAfter = 0;
  let feedUser = '';
  let feedLines = [];
  let feedTimer = null;
  function fillUserPick() {
    const sel = $('a-user');
    const want = sel.value;
    const opts = ['<option value="">Everyone</option>'].concat((data ? data.users : []).map((u) =>
      `<option value="${u.id}">${esc(u.name ? `${u.name} (${u.email})` : u.email)}</option>`));
    if (sel.dataset.sig !== opts.join('')) { sel.innerHTML = opts.join(''); sel.dataset.sig = opts.join(''); sel.value = want; }
  }
  function renderFeed() {
    const q = $('a-q').value.trim().toLowerCase();
    const shown = q ? feedLines.filter((e) => e.text.toLowerCase().includes(q) || (e.email || '').includes(q)) : feedLines;
    const box = $('a-feed');
    const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
    box.innerHTML = shown.map((e) => lineHtml(e, !feedUser)).join('');
    $('a-empty').hidden = !!shown.length;
    if (atBottom) box.scrollTop = box.scrollHeight;
  }
  async function pollFeed(reset) {
    clearTimeout(feedTimer);
    if ($('t-activity').hidden || !$('v-login').hidden) return;
    if (reset || feedUser !== $('a-user').value) { feedUser = $('a-user').value; feedAfter = 0; feedLines = []; }
    try {
      const r = await api(`/admin/api/activity?after=${feedAfter}&limit=300${feedUser ? `&user=${feedUser}` : ''}`);
      if (r.events.length) {
        feedLines = feedLines.concat(r.events).slice(-1000);
        feedAfter = r.last_id;
        renderFeed();
      } else if (reset) renderFeed();
    } catch (e) { if (e instanceof SignedOut) { showLogin(); return; } }
    if ($('a-live').checked && !document.hidden) feedTimer = setTimeout(() => pollFeed(false), 3000);
  }
  $('a-user').addEventListener('change', () => pollFeed(true));
  $('a-q').addEventListener('input', renderFeed);
  $('a-live').addEventListener('change', () => { if ($('a-live').checked) pollFeed(false); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && !$('t-activity').hidden) pollFeed(false); });

  // ------------------------------------------------------------------ user details
  let openId = null;
  let drawerAfter = 0;
  async function openUser(id) {
    openId = id;
    $('d-del-confirm').hidden = true;
    $('d-pw').value = '';
    $('d-apps').innerHTML = '<li class="muted">Loading…</li>';
    $('d-feed').innerHTML = '';
    drawerAfter = 0;
    const u = userById(id);
    if (u) fillDrawer(u);
    $('drawer').hidden = false;
    await loadUser(id, false);
  }
  function closeDrawer() {
    $('drawer').hidden = true;
    openId = null;
  }
  document.querySelectorAll('[data-close]').forEach((el) => el.addEventListener('click', closeDrawer));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('drawer').hidden) closeDrawer(); });

  async function loadUser(id, quiet) {
    try {
      const r = await api(`/admin/api/users/${id}`);
      if (openId !== id) return;
      fillDrawer(r.user, quiet);
      renderData(r.data);
      renderApps(r.applications);
      const a = await api(`/admin/api/activity?user=${id}&after=${drawerAfter}&limit=150`);
      if (openId !== id) return;
      if (a.events.length) {
        const box = $('d-feed');
        box.insertAdjacentHTML('beforeend', a.events.map((e) => lineHtml(e, false)).join(''));
        while (box.children.length > 300) box.removeChild(box.firstChild);
        box.scrollTop = box.scrollHeight;
        drawerAfter = a.last_id;
      } else if (!drawerAfter) $('d-feed').innerHTML = '<li class="muted"><span></span><span class="x">Nothing reported yet.</span></li>';
    } catch (e) {
      if (!quiet) fail(e);
      if (e.message && /not there/.test(e.message)) closeDrawer();
    }
  }

  function fillDrawer(u, quiet) {
    $('d-name').textContent = u.name || u.email.split('@')[0];
    $('d-email').textContent = u.email;
    $('d-state').innerHTML = stateCell(u);
    if (!quiet || document.activeElement !== $('d-pro')) $('d-pro').checked = !!u.pro;
    $('d-stop').disabled = !u.online || u.stop_pending;
    $('d-stop').textContent = u.stop_pending ? 'Stopping…' : 'Stop now';
    if (!quiet || document.activeElement !== $('d-note')) $('d-note').value = u.note || '';
    $('d-del-email').textContent = u.email;
    const facts = [
      ['Applied today', fmtNum(u.applied_today)], ['Last 7 days', fmtNum(u.applied_week)], ['All time', fmtNum(u.applied_total)],
      ['Last application', u.last_applied_at ? when(u.last_applied_at) : 'none yet'],
      ['Problems today', fmtNum(u.problems_today)], ['AI today', `${fmtNum(u.ai_today)} requests${u.tokens_today ? `, ${fmtNum(u.tokens_today)} tokens` : ''}`],
      ['App', `${u.app_version ? `${u.flavor === 'play' ? 'Google Play' : u.flavor === 'github' ? 'GitHub' : ''} ${esc(u.app_version)}`
        + `${u.version_code ? ` (code ${u.version_code})` : ''}` : '—'}${u.device ? ` on ${esc(u.device)}` : ''}`],
      ['Applied with help today', fmtNum(u.helped_today)],
      ['Signed in on', `${u.devices} phone(s)`], ['Account created', when(u.created_at)],
      ['Signs in with', u.google && u.has_password ? 'Google, or email and password' : u.google ? 'Google' : 'Email and password'],
      ['Pro since', u.pro && u.pro_changed_at ? when(u.pro_changed_at) : '—'],
    ];
    $('d-facts').innerHTML = facts.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('');
    if (!quiet || ![$('d-cap'), $('d-caph')].includes(document.activeElement)) {
      $('d-cap').value = u.max_per_day == null ? '' : u.max_per_day;
      $('d-caph').value = u.max_per_hour == null ? '' : u.max_per_hour;
    }
  }

  function renderData(d) {
    if (!d) return;
    const n = d.counts || {};
    const g = d.drive || {};
    const kb = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`);
    const drive = !g.connected ? 'Not connected'
      : `${esc(g.email || 'Connected')}${g.error ? ` <span class="bad-text">· ${esc(g.error)}</span>`
        : g.synced_at ? ` <span class="muted">· synced ${ago(g.synced_at)}</span>` : ' <span class="muted">· setting up</span>'}`;
    const rows = [
      ['CV', d.cv ? `${esc(d.cv.name)} <span class="muted">· ${kb(d.cv.size)} · ${when(d.cv.at)}</span>` : 'none yet'],
      ['Setup answers', fmtNum(n.profile || 0)], ['Your info', `${fmtNum(n.facts || 0)} facts`],
      ['Jobs to apply / applied by hand', fmtNum(n.copilot || 0)], ['Auto apply job log', fmtNum(n.jobs || 0)],
      ['Last change', d.at ? when(d.at) : '—'], ['Google Drive', drive],
    ];
    $('d-data').innerHTML = rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('');
  }

  function renderApps(apps) {
    if (!apps.length) {
      $('d-apps').innerHTML = '<li class="muted">Nothing reported yet.</li>';
      return;
    }
    $('d-apps').innerHTML = apps.map((a) => {
      const [label, tone] = STATUS[a.status] || [a.status, ''];
      const title = a.url ? `<a href="${esc(a.url)}" target="_blank" rel="noopener noreferrer">${esc(a.title || a.job_id)}</a>` : esc(a.title || a.job_id);
      const via = { linkedin: 'LinkedIn, by hand', company: 'Company site', email: 'Email' }[a.channel] || '';
      const stage = a.stage && a.stage !== 'applied' ? ` · ${a.stage[0].toUpperCase()}${a.stage.slice(1)}` : '';
      return `<li><span class="t">${title}</span><span class="tag ${tone}">${esc(label)}${stage}${a.score != null ? ` · ${a.score}/10` : ''}</span>`
        + `${via ? `<span class="c">${esc(via)}</span>` : ''}`
        + `<span class="c">${esc(a.company || '')}</span><span class="w">${when(a.at)}</span></li>`;
    }).join('');
  }

  $('d-pro').addEventListener('change', async () => { if (openId) await setPro(openId, $('d-pro').checked, $('d-pro')); loadUser(openId, true); });
  $('d-stop').addEventListener('click', async () => { if (openId) await stopUser(openId, $('d-stop')); loadUser(openId, true); });
  $('d-caps-save').addEventListener('click', async () => {
    try {
      await api(`/admin/api/users/${openId}`, { max_per_day: $('d-cap').value.trim(), max_per_hour: $('d-caph').value.trim() });
      toast('Limits saved. The app uses them from its next check (within 30 seconds while it applies).');
      refresh();
    } catch (e) { fail(e); }
  });
  $('d-revoke').addEventListener('click', async () => {
    try {
      await api(`/admin/api/users/${openId}`, { revoke_sessions: true });
      toast('Signed out on every phone.');
      loadUser(openId, true);
    } catch (e) { fail(e); }
  });
  $('d-note-save').addEventListener('click', async () => {
    try {
      await api(`/admin/api/users/${openId}`, { note: $('d-note').value });
      toast('Note saved.');
      refresh();
    } catch (e) { fail(e); }
  });
  $('d-pw-save').addEventListener('click', async () => {
    const pw = $('d-pw').value;
    if (pw.length < 8) { toast('Use at least 8 characters.', true); return; }
    try {
      await api(`/admin/api/users/${openId}`, { password: pw });
      toast('Password changed. The user was signed out on every phone.');
      $('d-pw').value = '';
      loadUser(openId, true);
    } catch (e) { fail(e); }
  });
  $('d-del').addEventListener('click', () => { $('d-del-confirm').hidden = false; });
  $('d-del-no').addEventListener('click', () => { $('d-del-confirm').hidden = true; });
  $('d-del-yes').addEventListener('click', async () => {
    try {
      await api(`/admin/api/users/${openId}/delete`, {});
      toast('User deleted.');
      closeDrawer();
      refresh();
    } catch (e) { fail(e); }
  });

  // ------------------------------------------------------------------ settings
  function fillSettings() {
    if (!data) return;
    const s = data.settings;
    $('s-key').value = '';
    $('s-key').placeholder = s.has_key ? 'Saved. Paste a new key to replace it' : 'sk-or-v1-…';
    $('s-key-state').innerHTML = s.has_key ? `<span class="ok-text">Saved</span> (${esc(s.key_hint)})` : '<span class="bad-text">No key yet</span>';
    $('btn-clear-key').hidden = !s.has_key;
    $('s-model').value = s.model || '';
    $('s-free').value = s.free_ai_per_day;
    $('s-pro').value = s.pro_ai_per_day;
    $('s-cap').value = s.max_per_day;
    $('s-caph').value = s.max_per_hour;
    $('s-announce').value = s.announcement || '';
    $('s-minv-github').value = s.min_version_github;
    $('s-minv-play').value = s.min_version_play;
    $('s-playurl').value = s.play_url || '';
    const vs = (data.totals && data.totals.versions) || [];
    $('s-versions').innerHTML = vs.length ? vs.map((v) => `<li><span>${v.flavor === 'play' ? 'Google Play' : v.flavor === 'github' ? 'GitHub' : 'Unknown'} `
      + `${esc(v.version)}${v.code ? ` <span class="muted">· code ${v.code}</span>` : ''}</span><b>${v.users} user${v.users === 1 ? '' : 's'}</b></li>`).join('')
      : '<li class="muted">No app has reported yet.</li>';
    $('s-signups').checked = !!s.signups_open;
    $('s-autopro').checked = !!s.new_users_pro;
    $('s-pwsignups').checked = !!s.password_signups;
    $('g-redirect').textContent = s.google_redirect || `${location.origin}/oauth/google/callback`;
    if (document.activeElement !== $('g-id')) $('g-id').value = s.google_client_id || '';
    $('g-secret').value = '';
    $('g-secret').placeholder = s.has_google_secret ? 'Saved. Paste a new one to replace it' : 'GOCSPX-…';
    const users = (data.totals && data.totals.drive) || 0;
    $('g-state').innerHTML = s.google_ready ? `<span class="ok-text">Ready</span> · ${users} user${users === 1 ? '' : 's'} connected`
      : (s.public_url ? '<span class="bad-text">Not set up yet</span>' : '<span class="bad-text">PUBLIC_URL is not set on the server</span>');
    $('btn-clear-google').hidden = !s.google_client_id && !s.has_google_secret;
  }
  async function saveSettings(body, msg) {
    try {
      const r = await api('/admin/api/settings', body);
      data.settings = r.settings;
      $('setup-warn').dataset.done = '';
      fillSettings();
      render();
      toast(msg || 'Saved.');
      return true;
    } catch (e) { fail(e); return false; }
  }
  $('f-ai').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const body = { model: $('s-model').value.trim() };
    if ($('s-key').value.trim()) body.openrouter_key = $('s-key').value.trim();
    saveSettings(body, body.openrouter_key ? 'Key and model saved. Every app uses them from the next AI request.' : 'Model saved.');
  });
  $('btn-test-ai').addEventListener('click', async () => {
    $('test-out').className = 'hint';
    $('test-out').textContent = 'Checking…';
    try {
      const r = await api('/admin/api/test-ai', { openrouter_key: $('s-key').value.trim(), model: $('s-model').value.trim() });
      $('test-out').className = 'hint ok-text';
      $('test-out').textContent = r.message;
    } catch (e) {
      if (e instanceof SignedOut) { showLogin(); return; }
      $('test-out').className = 'hint bad-text';
      $('test-out').textContent = e.message;
    }
  });
  $('btn-collect-jobs').addEventListener('click', async () => {
    const b = $('btn-collect-jobs');
    b.disabled = true;
    b.textContent = 'Collecting…';
    $('test-out').className = 'hint';
    $('test-out').textContent = 'Starting LinkedIn guest job collection & matching…';
    try {
      const r = await api('/admin/api/collect-jobs', {});
      $('test-out').className = 'hint ok-text';
      $('test-out').textContent = r.message || `Done! Collected & evaluated ${r.collected || 0} jobs.`;
      toast(r.message || `Collected & processed ${r.collected || 0} jobs.`);
    } catch (e) {
      if (e instanceof SignedOut) { showLogin(); return; }
      $('test-out').className = 'hint bad-text';
      $('test-out').textContent = e.message;
      toast(e.message, true);
    } finally {
      b.disabled = false;
      b.textContent = 'Collect Jobs Now';
    }
  });
  $('btn-clear-key').addEventListener('click', () => {
    // eslint-disable-next-line no-alert
    if (window.confirm('Remove the OpenRouter key? The apps cannot use the AI until you add one again.')) saveSettings({ clear_key: true }, 'Key removed.');
  });
  $('f-limits').addEventListener('submit', (ev) => {
    ev.preventDefault();
    saveSettings({ free_ai_per_day: $('s-free').value, pro_ai_per_day: $('s-pro').value, max_per_day: $('s-cap').value,
      max_per_hour: $('s-caph').value });
  });
  $('f-app').addEventListener('submit', (ev) => {
    ev.preventDefault();
    saveSettings({ announcement: $('s-announce').value.trim(), min_version_github: $('s-minv-github').value || 0,
      min_version_play: $('s-minv-play').value || 0, play_url: $('s-playurl').value.trim() },
    'Saved. Apps pick it up from their next check.');
  });
  $('f-google').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const body = { google_client_id: $('g-id').value.trim() };
    if ($('g-secret').value.trim()) body.google_client_secret = $('g-secret').value.trim();
    saveSettings(body, 'Saved. Users can connect Google Drive from the app (Settings > Google Drive).');
  });
  // The JSON file Google gives with the client: its ID and secret go in the form, then Save.
  $('g-json').addEventListener('change', async () => {
    const f = $('g-json').files[0];
    $('g-json').value = '';
    if (!f) return;
    try {
      const j = JSON.parse(await f.text());
      const c = j.web || j.installed || {};
      if (!c.client_id || !c.client_secret) throw new Error('This file has no client_id and client_secret.');
      const want = $('g-redirect').textContent.trim();
      if (want && Array.isArray(c.redirect_uris) && !c.redirect_uris.includes(want)) {
        toast(`Warning: this client's redirect URI is not ${want}. Add it in Google Cloud.`, true);
      }
      $('g-id').value = c.client_id;
      $('g-secret').value = c.client_secret;
      $('f-google').requestSubmit();
    } catch (e) { toast(e.message || 'Could not read that file.', true); }
  });
  $('btn-clear-google').addEventListener('click', () => {
    // eslint-disable-next-line no-alert
    if (window.confirm('Remove the Google client? Google Drive stops syncing for every user until you add one again.')) {
      saveSettings({ clear_google: true }, 'Google client removed.');
    }
  });
  $('f-accounts').addEventListener('submit', (ev) => {
    ev.preventDefault();
    saveSettings({ signups_open: $('s-signups').checked, new_users_pro: $('s-autopro').checked,
      password_signups: $('s-pwsignups').checked });
  });
  document.querySelectorAll('[data-copy]').forEach((b) => b.addEventListener('click', async () => {
    const text = $(b.dataset.copy).textContent;
    try {
      await navigator.clipboard.writeText(text);
      toast('Copied.');
    } catch (e) {
      const r = document.createRange();
      r.selectNodeContents($(b.dataset.copy));
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
      toast('Selected. Copy it with Ctrl+C.');
    }
  }));

  boot();
})();
