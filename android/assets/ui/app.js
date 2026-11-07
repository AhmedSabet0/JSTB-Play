/* JSTB (Android): setup, home, your jobs, applications, profile and settings. Talks to the app through window.AA.
   The Google Play build applies with you (Jobs: you send each application with the app's help); the GitHub build can
   also apply by itself (auto apply). */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // The app answers through window.__aaDone, so a slow request never freezes the screen.
  const pendingCalls = new Map();
  let callSeq = 0;
  window.__aaDone = (id, raw) => {
    const done = pendingCalls.get(id);
    if (done) { pendingCalls.delete(id); done(raw); }
  };
  function bridge(path, body) {
    if (!window.AA.call) return Promise.resolve(window.AA.api(path, body));
    return new Promise((resolve) => {
      const id = String(++callSeq);
      pendingCalls.set(id, resolve);
      window.AA.call(id, path, body);
    });
  }
  async function api(path, body) {
    if (!window.AA) throw new Error('The app is not ready');
    const raw = await bridge(path, body === undefined ? '' : JSON.stringify(body));
    let data = {};
    try { data = JSON.parse(raw); } catch (e) { /* not json */ }
    if (data.ok === false) throw new Error(data.error || 'Request failed');
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
  function store(key, value) {
    try {
      if (value === undefined) return localStorage.getItem(key);
      localStorage.setItem(key, value);
    } catch (e) { /* storage unavailable */ }
    return null;
  }

  const fmtTime = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const fmtWhen = (ts) => {
    const d = new Date(ts * 1000);
    return new Date().toDateString() === d.toDateString() ? fmtTime(ts)
      : d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  };
  const fmtDur = (sec) => {
    sec = Math.max(0, Math.round(sec));
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  };
  const plural = (n, one, many) => `${n} ${n === 1 ? one : (many || `${one}s`)}`;
  // Same count as the app: whole years since July of the graduation year.
  function yearsSince(y) {
    const year = Number(y);
    if (!/^\d{4}$/.test(String(y || '')) || year < 1950) return -1;
    const now = new Date();
    const start = new Date(now.getFullYear(), 0, 1);
    const t = now.getFullYear() + Math.floor((now - start) / 86400000) / 366;
    return Math.max(0, Math.floor(t - (year + 0.5)));
  }

  // ------------------------------------------------------------------ navigation
  const TITLES = { setup: 'JSTB', home: 'JSTB', queue: 'Your jobs', tracker: 'Applied', profile: 'Profile',
    settings: 'Settings', info: 'Your info', jobs: 'Auto apply history', log: 'Activity', update: 'JSTB' };
  const TABS = ['home', 'queue', 'tracker', 'profile'];
  const TAB_OF = { settings: 'profile', info: 'profile', jobs: 'profile', log: 'profile' };
  const auto = () => !!(state && state.auto_apply);
  const isPlay = () => !!(state && state.build && state.build.flavor === 'play');
  const mustUpdate = () => !!(state && state.account && state.account.update && state.account.update.required);
  let view = null;
  const history = [];
  function go(v, opts = {}) {
    if (mustUpdate() && v !== 'update') v = 'update';  // too old for the server: only the update screen
    if (TABS.includes(v) || v === 'setup' || v === 'update') history.length = 0;
    else if (view && view !== v && !opts.back) history.push(view);
    closeSheets();
    view = v;
    document.querySelectorAll('.view').forEach((s) => { s.hidden = s.id !== `v-${v}`; });
    $('bar-title').textContent = TITLES[v] || 'JSTB';
    const top = TABS.includes(v) || v === 'setup' || v === 'update';
    $('nav-back').hidden = top;
    $('nav-settings').hidden = !TABS.includes(v);
    document.querySelector('.logo').style.display = ['home', 'setup', 'update'].includes(v) ? '' : 'none';
    const tabs = v !== 'setup' && v !== 'update';
    $('tabbar').hidden = !tabs;
    document.body.classList.toggle('tabs', tabs);
    document.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === (TAB_OF[v] || v)));
    window.scrollTo(0, 0);
    if (v === 'settings') { loadSettings(); loadDrive(); }
    if (v === 'profile') { loadSettings(); renderProfile(); }
    if (v === 'info') loadInfo();
    if (v === 'jobs') loadJobs();
    if (v === 'queue') loadQueue();
    if (v === 'tracker') loadTracker();
    if (v === 'home') { loadQueue(true); loadTracker(true); }
    if (v === 'update') renderForceUpdate();
    if (v === 'log') { const box = $('log'); box.scrollTop = box.scrollHeight; window.scrollTo(0, document.body.scrollHeight); }
    renderBar();
    renderQuestionBar();
  }
  function back() {
    if (sheetOpen()) { closeSheets(); return true; }
    if (view === 'setup') {
      const steps = wSteps();
      const i = steps.indexOf(wStep);
      if (i > 0 && wStep !== 'welcome') { wShow(steps[i - 1]); return true; }
      if (state && state.setup.setup_done) { go('home'); return true; }
      return false;
    }
    if (view === 'home' || view === 'update') return false;
    if (TABS.includes(view)) { go('home'); return true; }
    go(history.pop() || 'home', { back: true });
    return true;
  }
  window.aaBack = () => back();
  $('nav-back').addEventListener('click', () => back());
  $('nav-settings').addEventListener('click', () => go('settings'));
  document.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => go(b.dataset.tab)));
  // The keyboard covers the bottom of the screen: the tabs step aside while you type.
  const TYPING = 'input:not([type=checkbox]):not([type=radio]):not([type=range]):not([type=file]), textarea, select';
  document.addEventListener('focusin', (e) => { if (e.target.matches && e.target.matches(TYPING)) document.body.classList.add('typing'); });
  document.addEventListener('focusout', () => setTimeout(() => {
    const a = document.activeElement;
    if (!a || !a.matches || !a.matches(TYPING)) document.body.classList.remove('typing');
  }, 60));
  document.querySelectorAll('[data-go]').forEach((b) => b.addEventListener('click', () => go(b.dataset.go)));
  document.querySelectorAll('[data-share]').forEach((b) => b.addEventListener('click',
    () => api('/api/share', { what: b.dataset.share }).catch((e) => toast(e.message, true))));
  document.querySelectorAll('[data-signin]').forEach((b) => b.addEventListener('click',
    () => api('/api/linkedin', {}).catch((e) => toast(e.message, true))));
  document.querySelectorAll('[data-perm]').forEach((b) => b.addEventListener('click',
    () => api('/api/permission', { which: b.dataset.perm }).catch((e) => toast(e.message, true))));
  window.addEventListener('scroll', () => document.querySelector('.appbar').classList.toggle('scrolled', window.scrollY > 4),
    { passive: true });

  // ------------------------------------------------------------------ state
  let state = null;
  let firstState = true;
  async function refresh() {
    try {
      state = await api(document.hidden ? '/api/state?bg=1' : '/api/state');   // bg: not "in use" (no syncs for it)
    } catch (e) {
      return;
    }
    document.body.classList.toggle('has-auto', auto());
    document.body.classList.toggle('no-auto', !auto());
    if (firstState) {
      firstState = false;
      if (mustUpdate()) go('update');
      else if (!state.setup || !state.setup.account) startSetup('account');
      else if (!state.setup.has_cv) startSetup('cv');
      else if (!state.setup.setup_done) startSetup(wFirst(state.setup));
      else go('home');
      setTimeout(takePending, 50);
    } else if (mustUpdate() && view !== 'update') {
      go('update');
    } else if (!mustUpdate() && view === 'update') {
      go(state.setup.setup_done ? 'home' : 'setup');
      if (!state.setup.setup_done) startSetup();
    }
    renderHome();
    const rsig = `${state.ready || 0}:${state.approved || 0}:${state.running ? 1 : 0}`;
    if (rsig !== readySig) { readySig = rsig; loadReady(); }
    renderDesk();
    renderAnnounce();
    renderForceUpdate();
    if (view === 'profile') renderProfile();
    renderBar();
    renderQuestion(state.running ? state.question : null);
    renderUpdate(state.update, state.running);
    if (view === 'setup') wLive();
    if (view === 'settings') settingsLive();
    const n = state.open_questions || 0;
    $('open-badge').hidden = !n;
    $('open-badge').textContent = n;
    if (n !== lastOpenCount) {
      lastOpenCount = n;
      if (view === 'info' && !$('open-list').contains(document.activeElement)) loadOpen();
    }
    $('info-sub').textContent = state.facts ? `${plural(state.facts, 'answer')} you can reuse, by category` : 'What forms ask about you, by category';
  }

  // ------------------------------------------------------------------ chic floating notification pill
  let popupPillTimer = null;
  function showPopupPill({ type = 'job', icon = '✨', title = '', sub = '', actText = 'View', onAct = null, duration = 8000 } = {}) {
    const pill = $('popup-pill');
    if (!pill) return;
    if (popupPillTimer) { clearTimeout(popupPillTimer); popupPillTimer = null; }
    pill.className = `popup-pill ${type}`;
    $('popup-pill-icon').textContent = icon;
    $('popup-pill-title').textContent = title;
    $('popup-pill-sub').textContent = sub;
    const actBtn = $('popup-pill-act');
    actBtn.textContent = actText;
    actBtn.onclick = (e) => {
      e.stopPropagation();
      pill.hidden = true;
      if (onAct) onAct();
    };
    pill.onclick = () => {
      pill.hidden = true;
      if (onAct) onAct();
    };
    $('popup-pill-close').onclick = (e) => {
      e.stopPropagation();
      pill.hidden = true;
    };
    pill.hidden = false;
    popupPillTimer = setTimeout(() => {
      pill.hidden = true;
    }, duration);
  }

  let lastAccCheck = Date.now();
  let lastLists = 0;
  let lastQuestionSeen = null;
  async function loop() {
    await refresh();
    if (state) {
      const q = state.question;
      if (q && q.text && q.text !== lastQuestionSeen) {
        lastQuestionSeen = q.text;
        showPopupPill({
          type: 'question',
          icon: '❓',
          title: 'Question for Application',
          sub: q.text,
          actText: 'Answer',
          onAct: () => { go('questions'); }
        });
      }
    }
    if (state && state.setup.setup_done && !document.hidden && Date.now() - lastLists > 20000
      && ['home', 'queue', 'tracker'].includes(view) && !sheetOpen()) {
      lastLists = Date.now();
      loadQueue(true);
      loadTracker(true);
    }
    if (state && state.setup.account && !acc().pro && !document.hidden && Date.now() - lastAccCheck > 60000) {
      lastAccCheck = Date.now();
      api('/api/account/refresh', {}).catch(() => {});
    }
    setTimeout(loop, document.hidden ? 20000 : 1000);  // the app is in the background: barely poll
  }

  function renderBar() {
    const el = $('bar-state');
    if (!state || view !== 'home' || !state.running) { el.hidden = true; return; }
    const st = state.status || {};
    const wait = ['waiting', 'signin', 'question'].includes(st.state);
    el.hidden = false;
    el.className = `bar-state${wait ? ' wait' : ''}`;
    el.textContent = st.state === 'question' ? 'Question' : st.state === 'signin' ? 'Sign in' : wait ? 'Waiting'
      : state.options && state.options.no_submit ? 'Test run' : 'Applying';
  }

  // ------------------------------------------------------------------ setup wizard
  // The Google Play build needs no LinkedIn sign-in (you apply in the LinkedIn app) and no background work.
  const wSteps = () => ['account', 'cv', 'answers', 'linkedin', 'done'];
  const wParts = () => ['account', 'cv', 'answers', 'linkedin'];
  let wStep = null;
  let profileData = null;   // GET /api/profile
  let suggest = null;       // read from the CV
  let suggesting = false;

  function wFirst(setup) {
    if (!setup.account) return 'account';
    if (!setup.has_cv) return 'cv';
    if (!setup.profile_done) return 'answers';
    if (!setup.signed_in) return 'linkedin';
    return 'done';
  }
  async function startSetup(step) {
    go('setup');
    await loadProfile();
    wShow(step || wFirst(state.setup));
    kickSuggest(false);
  }

  // Sign in with Google comes first when the server offers it (and has its address built in).
  const googleFirst = () => !!acc().server_built_in && !acc().signed_in && !emailAllowed();   // Google only
  let welcomeInfo = false;
  function wShow(step) {
    wStep = step;
    if (step === 'welcome' && !welcomeInfo && acc().server_built_in && !acc().signed_in) {
      welcomeInfo = true;
      api('/api/account/info', {}).then((r) => { if (state) state.account = r.account; wLive(); }).catch(() => {});
    }
    document.querySelectorAll('.step').forEach((s) => { s.hidden = s.dataset.step !== step; });
    const parts = wParts();
    const at = parts.indexOf(step);
    $('w-progress').hidden = at < 0;
    $('w-progress').innerHTML = parts.map((p, i) => `<i class="${i < at ? 'done' : i === at ? 'now' : ''}"></i>`).join('');
    if (step === 'account') wAccState();
    if (step === 'cv') cvStates();
    if (step === 'answers') {
      renderProfileForm($('w-answers'), true);
      wSugState();
    }
    if (step === 'jobs') wJobs();
    wLive();
    window.scrollTo(0, 0);
  }

  // Parts of the wizard that follow the app's state (sign-in, permissions, CV).
  function wLive() {
    if (!state || view !== 'setup') return;
    const s = state.setup;
    const next = $('w-next');
    const backBtn = $('w-back');
    backBtn.hidden = wStep === 'welcome';
    backBtn.textContent = 'Back';
    const g1 = wStep === 'welcome' && googleFirst();
    $('w-welcome-acc').textContent = googleFirst() ? 'Sign in with Google' : 'Create your account';
    const labels = { welcome: g1 ? 'Continue with Google' : 'Get started', account: accLabel(), cv: 'Continue', answers: 'Save and continue',
      jobs: 'Continue', linkedin: s.signed_in ? 'Continue' : 'Skip for now', done: auto() ? 'Start applying' : 'Find jobs' };
    if (!next.dataset.busy) next.textContent = labels[wStep] || 'Continue';
    next.disabled = (wStep === 'cv' && !s.has_cv) || !!next.dataset.busy;
    if (wStep === 'linkedin') {
      $('w-li-ok').hidden = !s.signed_in;
      $('w-li-btn').hidden = !!s.signed_in;
    }
    if (wStep === 'done') {
      permStates(document.querySelector('.step[data-step="done"]'));
      const dr = (state.sync || {}).drive || {};
      $('w-drive-done').hidden = !dr.connected;
      $('w-drive-done-text').innerHTML = dr.connected ? `Google Drive is on: your <b>JSTB</b> folder${dr.email ? ` in ${esc(dr.email)}` : ''} keeps your CV, answers and jobs.` : '';
    }
    if (wStep === 'cv') cvStates();
  }

  async function wNext() {
    const next = $('w-next');
    if (next.disabled) return;
    try {
      if (wStep === 'welcome' && googleFirst()) await googleStart('welcome');
      else if (wStep === 'welcome') wShow('account');
      else if (wStep === 'account') await wAccount();
      else if (wStep === 'cv') wShow('answers');
      else if (wStep === 'answers') { if (await saveProfile($('w-answers'))) wShow('linkedin'); }
      else if (wStep === 'linkedin') wShow('done');
      else if (wStep === 'done') await finishSetup(auto());
    } catch (e) {
      toast(e.message, true);
    }
  }
  $('w-next').addEventListener('click', wNext);
  $('w-back').addEventListener('click', () => back());

  function busy(btn, text) {
    if (text) { btn.dataset.busy = '1'; btn.textContent = text; btn.disabled = true; } else { delete btn.dataset.busy; btn.disabled = false; }
    wLive();
  }

  // ------------------------------------------------------------------ your account (first step)
  let accMode = 'signup';
  let accInfoAsked = false;
  const acc = () => (state && state.account) || {};
  // Google only, unless the admin allows email accounts too (Settings > New accounts with an email and a password).
  const emailAllowed = () => !!acc().google_signin && acc().password_signups === true;
  function accLabel() {
    if (acc().signed_in) return 'Continue';
    if (!emailAllowed()) return 'Continue with Google';
    return accMode === 'signup' ? 'Create account' : 'Sign in';
  }
  function setAccMode(mode) {
    if (mode === 'signup' && !pwSignups()) mode = 'login';   // new accounts are made with Google
    accMode = mode;
    document.querySelectorAll('[data-accmode]').forEach((b) => b.classList.toggle('on', b.dataset.accmode === mode));
    $('w-acc-name-field').hidden = mode !== 'signup';
    $('w-acc-pw').autocomplete = mode === 'signup' ? 'new-password' : 'current-password';
    $('w-acc-pw-hint').hidden = mode !== 'signup';
    const em = emailAllowed();
    $('w-acc-title').textContent = em ? (mode === 'signup' ? 'Create your account' : 'Sign in') : 'Sign in with Google';
    $('w-email-box').hidden = !em;       // accounts are made and opened with Google only
    $('w-google-box').hidden = false;
    $('w-google').hidden = !em;          // Google only: the big button at the bottom is Continue with Google
    $('w-or').hidden = !em;
    $('w-acc-err').hidden = true;
    accHelp();
    wLive();
  }
  const google = () => !!acc().google_signin;
  const pwSignups = () => acc().password_signups !== false;
  document.querySelectorAll('[data-accmode]').forEach((b) => b.addEventListener('click', () => setAccMode(b.dataset.accmode)));
  function accHelp() {
    const c = acc().contact;
    $('w-acc-help').hidden = !(c && accMode === 'login');
    if (c) $('w-acc-help').innerHTML = `Forgot your password? Email <a href="mailto:${esc(c)}">${esc(c)}</a>.`;
  }
  function wAccState() {
    const a = acc();
    $('w-acc-ok').hidden = !a.signed_in;
    $('w-acc-form').hidden = !!a.signed_in;
    $('w-acc-who').textContent = a.email || '';
    $('w-acc-lead').textContent = a.signed_in ? 'You are signed in. Continue to add your CV.'
      : 'Continue with Google to make your account or to sign in. It connects the AI that reads jobs and fills in forms, and keeps your data in your Google Drive.';
    if (a.signed_in) { $('w-acc-title').textContent = 'Your account'; return; }
    $('w-acc-server-field').hidden = !!a.server_built_in;
    if (!a.server_built_in && !$('w-acc-server').value) $('w-acc-server').value = (a.server || '').replace(/^https:\/\//, '');
    if (a.email && !$('w-acc-email').value) { $('w-acc-email').value = a.email; setAccMode('login'); } else setAccMode(accMode);
    if (!accInfoAsked && (a.server_built_in || a.server)) { accInfoAsked = true; loadAccInfo(); }
  }
  async function loadAccInfo(server) {
    try {
      const r = await api('/api/account/info', server ? { server } : {});
      state.account = r.account;
      setAccMode(accMode);
      if (r.server && r.server.signups_open === false) {
        $('w-acc-note').textContent = 'New accounts are closed right now. You can still sign in.';
        $('w-acc-note').hidden = false;
        setAccMode('login');
      }
      accHelp();
    } catch (e) { /* offline: the form still works */ }
  }
  // After signing in: an account used before brings its CV, answers and jobs (from your account, or your Drive).
  async function afterSignIn(restore) {
    if (restore) {
      busy($('w-next'), 'Getting your data…');
      try { await api('/api/sync/now', {}); } catch (e) { /* it syncs again by itself */ }
    }
    await refresh();
    busy($('w-next'));
    if (state.setup.setup_done && state.setup.has_cv) {
      go('home');
      toast(restore && ((state.sync || {}).note || '').includes('from your account')
        ? 'Welcome back. Your CV, answers and jobs are on this phone.' : 'Signed in.');
      return;
    }
    await loadProfile();
    const first = wFirst(state.setup);
    wShow(!state.setup.has_cv ? 'cv' : (first === 'welcome' || first === 'account' ? 'cv' : first));
    kickSuggest(false);
  }
  async function googleStart(where) {
    const a = acc();
    const body = {};
    const err = where === 'welcome' ? $('w-welcome-err') : $('w-acc-err');
    if (!a.server_built_in) {
      body.server = $('w-acc-server').value.trim();
      if (!body.server) { err.textContent = 'Type the server address.'; err.hidden = false; return; }
    }
    err.hidden = true;
    try {
      await api('/api/account/google', body);
      $('w-google-wait').hidden = false;
      $('w-welcome-wait').hidden = where !== 'welcome';
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    }
  }
  $('w-google').addEventListener('click', () => googleStart('account'));

  // Back in the app while a Google sign-in waits, without the link (the browser did not open the app): the app asks
  // the server whether Google's page is done. The link, when it comes, is handled first.
  let googleClaiming = false;
  function googleResumed() {
    if (googleClaiming || $('w-google-wait').hidden || acc().signed_in) return;
    googleClaiming = true;
    setTimeout(async () => {
      try {
        if ($('w-google-wait').hidden || acc().signed_in) return;
        const r = await api('/api/account/google-claim', {});
        if (r.pending || $('w-google-wait').hidden) return;
        window.aaGoogle(JSON.stringify(r));
      } catch (e) {
        window.aaGoogle(JSON.stringify({ ok: false, error: e.message }));
      } finally {
        googleClaiming = false;
      }
    }, 1500);
  }
  // Back from Google's page (autoapply://auth): signed in, or why not.
  window.aaGoogle = async (raw) => {
    let r = {};
    try { r = JSON.parse(raw); } catch (e) { r = { ok: false, error: 'Could not sign in. Try again.' }; }
    $('w-google-wait').hidden = true;
    $('w-welcome-wait').hidden = true;
    if (r.ok === false) {
      if (view === 'setup' && wStep === 'welcome') { $('w-welcome-err').textContent = r.error; $('w-welcome-err').hidden = false; }
      else if (view === 'setup' && wStep === 'account') { $('w-acc-err').textContent = r.error; $('w-acc-err').hidden = false; }
      else toast(r.error, true);
      return;
    }
    if (view !== 'setup' && state && state.setup.setup_done && acc().signed_in) { refresh(); return; }   // done already
    if (view !== 'setup') await startSetup('account');
    await afterSignIn(true);
  };

  async function wAccount() {
    const a = acc();
    if (a.signed_in) { await afterSignIn(!state.setup.has_cv); return; }
    if (!emailAllowed()) { await googleStart('account'); return; }   // Google only
    const err = $('w-acc-err');
    err.hidden = true;
    const body = { email: $('w-acc-email').value.trim(), password: $('w-acc-pw').value };
    if (accMode === 'signup') body.name = $('w-acc-name').value.trim();
    if (!a.server_built_in) body.server = $('w-acc-server').value.trim();
    let problem = '';
    if (!a.server_built_in && !body.server) problem = 'Type the server address.';
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(body.email)) problem = 'Type your email address.';
    else if (accMode === 'signup' && body.password.length < 8) problem = 'Use a password of at least 8 characters.';
    else if (!body.password) problem = 'Type your password.';
    if (problem) { err.textContent = problem; err.hidden = false; return; }
    busy($('w-next'), accMode === 'signup' ? 'Creating your account…' : 'Signing in…');
    try {
      await api(`/api/account/${accMode}`, body);
      $('w-acc-pw').value = '';
      busy($('w-next'));
      await afterSignIn(accMode === 'login');
    } catch (e) {
      busy($('w-next'));
      if (/already exists/i.test(e.message)) setAccMode('login');
      err.textContent = e.message;
      err.hidden = false;
    }
  }
  // A server you type (when none is built in): what it offers (Continue with Google, new accounts) shows once it is known.
  $('w-acc-server').addEventListener('change', () => {
    const v = $('w-acc-server').value.trim();
    if (v && !acc().server_built_in) loadAccInfo(v);
  });
  ['w-acc-email', 'w-acc-pw', 'w-acc-name', 'w-acc-server'].forEach((id) => $(id).addEventListener('keydown', (e) => {
    if (e.key === 'Enter') wNext();
  }));

  async function wSaveJobs() {
    const titles = wTitleEditor.get();
    if (!titles.length) { toast('Add at least one job title.', true); return false; }
    await api('/api/settings', { KEYWORDS: titles.join('\n'), LOCATION: $('w-location').value.trim() });
    wTitleEditor.clean();
    sTitleEditor.clean();
    await refresh();
    return true;
  }

  async function finishSetup(start) {
    await api('/api/setup', { done: true });
    if (!auto()) {
      await refresh();
      go('home');
      return;
    }
    if (start) {
      busy($('w-next'), 'Starting…');
      try {
        await startBot('apply');
      } finally {
        busy($('w-next'));
      }
    }
    await refresh();
    go('home');
  }

  // Read phone, city, graduation year, English and job titles from the CV once (AI).
  async function kickSuggest(force) {
    if (suggesting || !state || !state.setup.has_cv) return;
    if (suggest && !force) return;
    suggesting = true;
    wSugState();
    try {
      const r = await api('/api/profile/suggest', { force: !!force });
      suggest = r.suggest || null;
      if (r.problem) suggestProblem = r.problem;
    } catch (e) {
      suggestProblem = e.message;
    } finally {
      suggesting = false;
    }
    wSugState();
    if (view === 'setup' && wStep === 'answers') fillSuggestions($('w-answers'));
    if (view === 'setup' && wStep === 'jobs') wJobs();
  }
  let suggestProblem = '';
  function wSugState() {
    const el = $('w-sug-state');
    if (suggesting) { el.hidden = false; el.textContent = 'Reading your CV to fill in what it can…'; return; }
    el.hidden = !suggest;
    if (suggest) el.textContent = 'Answers marked “from your CV” were filled in by the AI. Check them.';
  }

  // ------------------------------------------------------------------ the setup answers form
  const PF = [
    { k: 'phone', label: 'Mobile number', kind: 'tel', ph: '01012345678', req: true },
    { k: 'city', label: 'City you live in', kind: 'text', ph: 'Cairo, Egypt' },
    { k: 'graduation_year', label: 'Year you graduated', kind: 'year', ph: '2019', req: true },
    { k: 'expected_salary', label: 'Expected monthly salary', kind: 'money', ph: '30000', req: true },
    { k: 'current_salary', label: 'Current monthly salary', kind: 'money', ph: '20000' },
    { k: 'notice_days', label: 'Notice period', kind: 'days', req: true },
    { k: 'military', label: 'Military service', kind: 'pick', options: ['Completed', 'Exempted', 'Postponed', 'Not applicable'] },
    { k: 'english', label: 'English', kind: 'pick', options: ['Native', 'Fluent', 'Professional', 'Good', 'Basic'] },
    { k: 'onsite', label: 'OK to work from the office?', kind: 'yesno' },
    { k: 'relocate', label: 'Willing to move to another city?', kind: 'yesno' },
    { k: 'driving', label: 'Driving license?', kind: 'yesno' },
    { k: 'authorized', label: 'Countries you can work in without a visa', kind: 'text', ph: 'Egypt' },
    { k: 'sponsorship', label: 'Need a visa sponsor?', kind: 'yesno',
      help: 'Used when a form does not name the country. For the countries above the app answers No, for others Yes.' },
  ];
  const DEFAULTS = { onsite: 'Yes', sponsorship: 'No', authorized: 'Egypt', currency: 'EGP' };
  const NOTICE_CHOICES = [['0', 'Immediately'], ['15', '15 days'], ['30', '30 days'], ['60', '60 days'], ['90', '90 days']];

  async function loadProfile() {
    try {
      profileData = await api('/api/profile');
      if (profileData.suggest) suggest = profileData.suggest;
    } catch (e) { toast(e.message, true); }
  }

  function renderProfileForm(box, wizard) {
    const saved = (profileData && profileData.profile) || {};
    const done = !!saved.done;
    const currencies = (profileData && profileData.currencies) || ['EGP', 'USD', 'EUR', 'SAR', 'AED'];
    box.innerHTML = PF.map((f) => {
      let control = '';
      if (f.kind === 'tel' || f.kind === 'text') {
        control = `<input type="${f.kind === 'tel' ? 'tel' : 'text'}" data-k="${f.k}" placeholder="${esc(f.ph)}" autocomplete="off">`;
      } else if (f.kind === 'year') {
        control = `<input type="number" inputmode="numeric" data-k="${f.k}" placeholder="${esc(f.ph)}" min="1960" max="2035">
          <p class="pf-help" data-years></p>`;
      } else if (f.kind === 'money') {
        const first = f.k === 'expected_salary';
        control = `<div class="money"><input type="number" inputmode="numeric" data-k="${f.k}" placeholder="${esc(f.ph)}">${first
          ? `<select data-k="currency" aria-label="Currency">${currencies.map((c) => `<option>${c}</option>`).join('')}</select>`
          : '<span class="cur-label" aria-hidden="true">EGP</span>'}</div>`;
      } else if (f.kind === 'days') {
        control = `<div class="pick-row" data-days>${NOTICE_CHOICES.map(([v, l]) => `<button type="button" class="pick" data-v="${v}">${l}</button>`).join('')}</div>
          <div class="pf-days"><input type="number" inputmode="numeric" data-k="${f.k}" min="0" max="365" placeholder="Other"><span class="muted">days</span></div>`;
      } else if (f.kind === 'pick') {
        control = `<div class="pick-row" data-pick="${f.k}">${f.options.map((o) => `<button type="button" class="pick" data-v="${esc(o)}">${esc(o)}</button>`).join('')}</div>`;
      } else if (f.kind === 'yesno') {
        control = `<div class="seg" data-pick="${f.k}"><button type="button" data-v="Yes">Yes</button><button type="button" data-v="No">No</button></div>`;
      }
      return `<div class="pf" data-f="${f.k}"><div class="pf-label"><span>${esc(f.label)}<span class="sug" hidden>from your CV</span></span>${
        f.req ? '<span class="req">needed</span>' : ''}</div>${control}${f.help ? `<p class="pf-help">${esc(f.help)}</p>` : ''}</div>`;
    }).join('') + rulesBox();
    // values: saved, else read from the CV, else defaults
    for (const f of PF) {
      let v = saved[f.k] || '';
      if (!v && !done && DEFAULTS[f.k]) v = DEFAULTS[f.k];
      setPf(box, f.k, v);
    }
    setPf(box, 'currency', saved.currency || 'EGP');
    fillSuggestions(box);
    box.querySelectorAll('[data-pick] button').forEach((b) => b.addEventListener('click', () => {
      const group = b.closest('[data-pick]');
      const on = !b.classList.contains('on');
      group.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b && on));
      b.closest('.pf').classList.remove('missing');
    }));
    box.querySelectorAll('[data-days] .pick').forEach((b) => b.addEventListener('click', () => {
      box.querySelector('[data-k="notice_days"]').value = b.dataset.v;
      paintDays(box);
      b.closest('.pf').classList.remove('missing');
    }));
    box.querySelector('[data-k="notice_days"]').addEventListener('input', () => paintDays(box));
    box.querySelector('[data-k="graduation_year"]').addEventListener('input', () => paintYears(box));
    box.querySelector('[data-k="currency"]').addEventListener('change', () => paintCurrency(box));
    box.querySelectorAll('input').forEach((i) => i.addEventListener('input', () => {
      i.closest('.pf').classList.remove('missing');
      const tag = i.closest('.pf').querySelector('.sug');
      if (tag) tag.hidden = true;
    }));
    paintDays(box);
    paintYears(box);
    paintCurrency(box);
    if (!wizard) box.querySelectorAll('.req').forEach((r) => { r.hidden = true; });
  }

  function rulesBox() {
    return `<details class="rules"><summary>How the app answers questions</summary><ul>
      <li>Years of experience with something in your CV: the years since you graduated. Not in your CV: you are asked (or the AI answers, if you choose that).</li>
      <li>Questions that say “how many” get a number only.</li>
      <li>Notice period in days, or weeks or months when the question asks for them.</li>
      <li>Salary per month in your currency. If a question names another currency (USD, SAR…) it is converted with today's rate, and multiplied by 12 when it asks per year.</li>
      <li>Other questions: your info first, even when a form words the question differently; then you or the AI. Everything you answer joins your info, sorted into categories.</li>
      <li>Exchange rates by <a href="https://www.exchangerate-api.com" target="_blank" rel="noopener">ExchangeRate-API</a>.</li>
    </ul></details>`;
  }

  function setPf(box, k, v) {
    const input = box.querySelector(`input[data-k="${k}"], select[data-k="${k}"]`);
    if (input) { input.value = v == null ? '' : v; return; }
    const group = box.querySelector(`[data-pick="${k}"]`);
    if (group) group.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  }
  function getPf(box, k) {
    const input = box.querySelector(`input[data-k="${k}"], select[data-k="${k}"]`);
    if (input) return input.value.trim();
    const on = box.querySelector(`[data-pick="${k}"] button.on`);
    return on ? on.dataset.v : '';
  }
  // Fill empty answers from what the AI read in the CV, and mark them.
  function fillSuggestions(box) {
    if (!suggest || !box.querySelector('.pf')) return;
    for (const k of ['phone', 'city', 'graduation_year', 'english']) {
      const v = suggest[k];
      if (v && !getPf(box, k)) {
        setPf(box, k, v);
        const tag = box.querySelector(`.pf[data-f="${k}"] .sug`);
        if (tag) tag.hidden = false;
      }
    }
    paintYears(box);
  }
  function paintDays(box) {
    const v = box.querySelector('[data-k="notice_days"]').value.trim();
    box.querySelectorAll('[data-days] .pick').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  }
  function paintYears(box) {
    const y = yearsSince(box.querySelector('[data-k="graduation_year"]').value.trim());
    const el = box.querySelector('[data-years]');
    el.innerHTML = y >= 0 ? `So <b>${plural(y, 'year')}</b> of experience for skills in your CV.` : '';
  }
  function paintCurrency(box) {
    const c = box.querySelector('[data-k="currency"]').value || 'EGP';
    box.querySelectorAll('.cur-label').forEach((l) => { l.textContent = c; });
  }
  function collectProfile(box) {
    const out = {};
    let missing = 0;
    for (const f of PF) {
      out[f.k] = getPf(box, f.k);
      const bad = !!f.req && out[f.k] === '';
      box.querySelector(`.pf[data-f="${f.k}"]`).classList.toggle('missing', bad);
      if (bad) missing++;
    }
    out.currency = getPf(box, 'currency') || 'EGP';
    const y = Number(out.graduation_year);
    if (out.graduation_year && (y < 1960 || y > new Date().getFullYear() + 1)) {
      box.querySelector('.pf[data-f="graduation_year"]').classList.add('missing');
      missing++;
    }
    if (missing) {
      const first = box.querySelector('.pf.missing');
      if (first) first.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return null;
    }
    return out;
  }
  async function saveProfile(box) {
    const values = collectProfile(box);
    if (!values) { toast('Fill in the answers marked in red.', true); return false; }
    const r = await api('/api/profile', { profile: values });
    profileData = r;
    toast(r.replaced ? `Saved. ${plural(r.replaced, 'older saved answer')} now follow these.` : 'Answers saved.');
    await refresh();
    return true;
  }

  // ------------------------------------------------------------------ job titles
  function titleEditor(box, input, addBtn, sugBox) {
    let list = [];
    let sugs = [];
    let dirty = false;  // changed here and not saved yet
    const paint = () => {
      box.innerHTML = list.map((t, i) => `<span class="tchip">${esc(t)}<button type="button" data-i="${i}" aria-label="Remove ${esc(t)}">×</button></span>`).join('');
      box.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { list.splice(+b.dataset.i, 1); dirty = true; paint(); }));
      if (sugBox) {
        const left = sugs.filter((s) => !list.some((t) => t.toLowerCase() === s.toLowerCase()));
        sugBox.innerHTML = left.map((s) => `<button type="button">+ ${esc(s)}</button>`).join('');
        sugBox.querySelectorAll('button').forEach((b, i) => b.addEventListener('click', () => { add(left[i]); }));
      }
    };
    const add = (t) => {
      const v = String(t || '').trim();
      if (v && !list.some((x) => x.toLowerCase() === v.toLowerCase())) { list.push(v); dirty = true; }
      paint();
    };
    addBtn.addEventListener('click', () => { add(input.value); input.value = ''; input.focus(); });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addBtn.click(); } });
    return {
      get: () => { if (input.value.trim()) { add(input.value); input.value = ''; } return list.slice(); },
      set: (l) => { if (!dirty) { list = l.slice(); paint(); } },
      clean: () => { dirty = false; },
      suggest: (l) => { sugs = l || []; paint(); },
    };
  }
  const wTitleEditor = titleEditor($('w-titles'), $('w-title-add'), $('w-title-btn'), $('w-title-sugs'));
  const sTitleEditor = titleEditor($('s-titles'), $('s-title-add'), $('s-title-btn'), null);

  let wJobsFilled = false;
  async function wJobs() {
    if (!wJobsFilled) {
      wJobsFilled = true;
      try {
        const d = await api('/api/settings');
        const saved = String(d.KEYWORDS || '').split(';').map((x) => x.trim()).filter(Boolean);
        wTitleEditor.set(saved.length ? saved : (suggest && suggest.titles) || []);
        $('w-location').value = d.LOCATION || (suggest && suggest.location) || 'Egypt';
      } catch (e) { toast(e.message, true); }
    } else if (!wTitleEditor.get().length && suggest && suggest.titles) {
      wTitleEditor.set(suggest.titles);
    }
    if (!$('w-location').value && suggest && suggest.location) $('w-location').value = suggest.location;
    wTitleEditor.suggest((suggest && suggest.titles) || []);
  }

  // ------------------------------------------------------------------ CV upload (setup and settings)
  function cvStates() {
    if (!state) return;
    const s = state.setup;
    document.querySelectorAll('.drop').forEach((zone) => {
      if (zone.dataset.busy) return;
      zone.classList.toggle('has-cv', !!s.has_cv);
      zone.querySelector('.cv-title').textContent = s.has_cv ? s.cv_name : 'Choose your CV';
      zone.querySelector('.cv-state').textContent = s.has_cv
        ? `${s.cv_chars.toLocaleString()} characters read. Tap to choose another file.` : 'PDF or .txt';
    });
  }
  async function pdfText(file) {
    const pdfjs = await import('./pdfjs/pdf.min.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = './pdfjs/pdf.worker.min.mjs';
    const doc = await pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) }).promise;
    const pages = [];
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i);
      const content = await page.getTextContent();
      let t = '';
      for (const it of content.items) t += (it.str || '') + (it.hasEOL ? '\n' : ' ');
      pages.push(t.replace(/[ \t]+\n/g, '\n'));
    }
    return pages.join('\n\n');
  }
  function uploadCv(file, zone) {
    if (!file) return;
    if (file.size > 15 * 1024 * 1024) { toast('That file is too large.', true); return; }
    zone.dataset.busy = '1';
    zone.querySelector('.cv-state').textContent = `Reading ${file.name}…`;
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        let text = '';
        if (/\.pdf$/i.test(file.name)) text = await pdfText(file);
        const r = await api('/api/cv', { name: file.name, data: reader.result, text });
        if ($('cv-preview')) $('cv-preview').textContent = r.preview;
        if ($('cv-preview-box')) $('cv-preview-box').hidden = false;
        if ($('home-cv-preview')) $('home-cv-preview').textContent = r.preview;
        if ($('home-cv-preview-box')) $('home-cv-preview-box').hidden = false;
        toast('CV saved.');
        delete zone.dataset.busy;
        await refresh();
        cvStates();
        suggest = null;
        kickSuggest(true);
      } catch (e) {
        delete zone.dataset.busy;
        zone.querySelector('.cv-state').textContent = e.message;
        toast(e.message, true);
      }
    };
    reader.readAsDataURL(file);
  }
  document.querySelectorAll('.drop').forEach((zone) => {
    const input = zone.querySelector('.cv-input');
    input.addEventListener('change', (e) => { uploadCv(e.target.files[0], zone); input.value = ''; });
    ['dragenter', 'dragover'].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.add('over'); }));
    ['dragleave', 'drop'].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.remove('over'); }));
    zone.addEventListener('drop', (e) => uploadCv(e.dataTransfer.files[0], zone));
  });
  const homeCvViewBtn = $('home-cv-view-btn');
  if (homeCvViewBtn) {
    homeCvViewBtn.addEventListener('click', async () => {
      const box = $('home-cv-preview-box');
      if (!box.hidden) { box.hidden = true; return; }
      try {
        const r = await api('/api/cv');
        $('home-cv-preview').textContent = r.text || r.preview || 'No text extracted.';
        box.hidden = false;
      } catch (e) {
        toast(e.message, true);
      }
    });
  }

  // ------------------------------------------------------------------ background permissions
  const STRICT = /xiaomi|redmi|poco|huawei|honor|oppo|realme|oneplus|vivo|iqoo|tecno|infinix|itel|meizu|asus/;
  function permStates(root) {
    const perm = (state && state.permissions) || {};
    for (const k of ['notifications', 'battery']) {
      const ok = !!perm[k];
      root.querySelectorAll(`[data-perm="${k}"]`).forEach((b) => { b.hidden = ok; });
      root.querySelectorAll(`[data-perm-ok="${k}"]`).forEach((t) => { t.hidden = !ok; });
    }
    const maker = String(perm.maker || '');
    root.querySelectorAll('.maker-row').forEach((row) => {
      row.hidden = !STRICT.test(maker.toLowerCase());
      row.querySelector('.maker-text').textContent = `${maker} phones can stop apps in the background. In App info, allow Autostart and set Battery to “No restrictions”.`;
    });
  }

  // ------------------------------------------------------------------ home
  const STATE_TITLES = {
    idle: 'Ready', starting: 'Starting', signin: 'Sign in to LinkedIn', searching: 'Looking for jobs', working: 'Applying',
    waiting: 'Waiting', error: 'Needs your attention', question: 'Waiting for your answer',
  };
  let countdownAt = 0;
  function renderHome() {
    const s = state;
    if (!s) return;
    const st = s.status || {};
    const running = s.running;
    const test = running && s.options && s.options.no_submit;
    const k = s.stats;
    const pct = Math.min(1, k.today / Math.max(1, k.per_day));
    $('ring-fill').style.strokeDashoffset = String(326.73 * (1 - pct));
    $('k-today').textContent = k.today;
    $('k-today-of').textContent = `of ${k.per_day} a day`;
    const a = s.account || {};
    const tone = running ? (['waiting', 'signin', 'question'].includes(st.state) ? 'wait' : 'run')
      : (s.setup.account && s.setup.has_cv && !a.pro ? 'wait' : st.state === 'error' ? 'err' : '');
    $('state-dot').className = `dot ${tone}`;
    $('state-title').textContent = running ? (test && st.state === 'working' ? 'Test run: filling a form'
      : STATE_TITLES[st.state] || 'Working') : (s.setup.account && s.setup.has_cv && !a.pro ? 'Auto apply needs Pro'
        : st.state === 'error' ? STATE_TITLES.error : 'Ready');
    const idleMsg = !s.setup.account || !s.setup.has_cv ? 'Finish the setup to start.'
      : !a.pro ? 'Auto apply needs Pro on your account. The Jobs tab works without it: the app fills forms and writes cover letters.'
      : 'Press Start. You can lock the phone; it keeps going and sends you a summary.';
    const lastMsg = s.setup.account && s.setup.has_cv && !a.pro ? idleMsg
      : st.message === 'Stopped' ? 'Stopped. Press Start to go on.'
        : st.message && !['Ready', 'Finished'].includes(st.message) ? st.message : idleMsg;
    $('state-msg').textContent = running ? (st.message || 'Working…') : lastMsg;
    // Not active yet is explained by the banner and the line above, not as an error.
    const err = !running && st.state === 'error' && st.error && !(s.setup.account && !a.pro && /needs Pro/.test(st.error));
    $('error-msg').hidden = !err;
    if (err) $('error-msg').textContent = st.error;

    const job = running && st.job && (st.job.title || st.job.url) ? st.job : null;
    const cj = $('current-job');
    cj.hidden = !job;
    if (job) {
      cj.href = job.url;
      cj.innerHTML = `<b>${esc(job.title || 'Opening a job…')}</b><span>${esc(job.company || '')}</span>`;
    }
    countdownAt = running && st.next_slot_at && st.next_slot_at > s.now ? st.next_slot_at - s.now + Date.now() / 1000 : 0;
    $('countdown-label').textContent = /Round finished/.test(st.message || '') ? 'Next search in'
      : /daily limit/i.test(st.message || '') ? 'Starts again in' : 'Next application in';
    tick();

    const ready = s.setup.account && s.setup.has_cv;
    const notActive = ready && !a.pro && !running;
    $('btn-start').hidden = running;
    $('btn-start').disabled = !ready;
    $('btn-stop').hidden = !running;
    if (!running) $('btn-stop').disabled = false;
    $('btn-test').hidden = running || !ready;
    $('btn-live').hidden = !running;

    const sess = running ? s.session : (s.session || s.last_session);
    const sessMode = sess ? sess.mode : 'apply';
    $('k-sess').textContent = sess ? (sessMode === 'apply' ? sess.applied.length : sess.filled.length) : 0;
    $('k-sess-label').textContent = sessMode === 'test' ? 'filled (test run)' : 'this session';
    $('k-week').textContent = k.week == null ? 0 : k.week;
    $('k-total').textContent = k.total;
    renderBrief(sess, running);
    const inBrief = new Set(((sess && sess.applied) || []).map((x) => x.id));
    renderRecent((s.recent || []).filter((x) => !inBrief.has(x.id)).slice(0, 5));
    const homeBadge = $('home-cv-badge');
    const homeView = $('home-cv-view-btn');
    if (homeBadge) {
      if (s.setup && s.setup.has_cv) {
        homeBadge.textContent = 'Active ✓';
        homeBadge.className = 'tag good';
        if (homeView) homeView.hidden = false;
      } else {
        homeBadge.textContent = 'Missing CV';
        homeBadge.className = 'tag warn';
        if (homeView) homeView.hidden = true;
      }
    }
    cvStates();
    renderBanners();
  }

  function tick() {
    const left = countdownAt - Date.now() / 1000;
    $('countdown').hidden = !(countdownAt && left > 0);
    if (countdownAt && left > 0) $('countdown-val').textContent = fmtDur(left);
  }
  setInterval(tick, 1000);

  function renderBanners() {
    const s = state;
    const items = [];
    const perm = s.permissions || {};
    if (!s.setup.account || !s.setup.has_cv) {
      items.push(['', '<b>Finish the setup</b><span>The app needs your account and your CV before it can apply.</span>',
        '<button class="btn primary" data-act="setup" type="button">Continue the setup</button>']);
    } else if (!(s.account || {}).pro && auto()) {
      items.push(['info', `<b>Auto apply needs Pro</b><span class="who">Signed in as ${esc((s.account || {}).email)}. Applying in the background and its notifications start once your account has Pro. Until then the Jobs tab fills forms and writes cover letters for you.</span>`,
        '<button class="btn" data-act="acc-check" type="button">Check again</button>']);
    } else if (!s.setup.signed_in && auto()) {
      items.push(['', '<b>Sign in to LinkedIn</b><span>Easy Apply only works from a signed-in account. You sign in once.</span>',
        '<button class="btn primary" data-act="signin" type="button">Sign in to LinkedIn</button>']);
    }
    const dr = (s.sync || {}).drive;
    if (dr && dr.connected && dr.reconnect) {
      items.push(['', '<b>Google Drive needs you to connect it again</b><span>Until then your CV, answers and jobs stay in your account, but not in your Drive.</span>',
        '<button class="btn" data-act="drive" type="button">Connect again</button>']);
    }
    const n = s.open_questions || 0;
    if (n) {
      items.push(['', `<b>${n === 1 ? 'A question is' : `${n} questions are`} waiting for your answer</b><span>Answer once and the jobs that waited are tried again by themselves.</span>`,
        '<button class="btn" data-act="answers" type="button">Answer</button>']);
    }
    if (auto() && (!perm.notifications || !perm.battery)) {
      items.push(['info', '<b>Keep it working with the screen off</b><span>Allow notifications and background work.</span>',
        `${perm.notifications ? '' : '<button class="btn" data-perm="notifications" type="button">Notifications</button>'}${
          perm.battery ? '' : '<button class="btn" data-perm="battery" type="button">Background</button>'}`]);
    }
    const u = s.update || {};
    const dst = (u.download || {}).state;
    if (!u.disabled && u.last && u.last.available && dst === 'ready') {
      items.push(['info', `<b>Version ${esc(u.last.version)} is downloaded</b><span>It installs by itself when the screen turns off. Your jobs, answers and sign-ins stay.</span>`,
        `<button class="btn" data-act="install-now" type="button"${s.running ? ' disabled' : ''}>Install now</button>`]);
    } else if (!u.disabled && u.last && u.last.available && !['downloading', 'installing', 'confirm'].includes(dst)) {
      items.push(['info', `<b>Version ${esc(u.last.version)} is ready</b><span>Your answers and sign-in stay.</span>`,
        '<button class="btn" data-act="update" type="button">See the update</button>']);
    }
    const sig = JSON.stringify(items);
    const box = $('banners');
    if (box.dataset.sig === sig) return;
    box.dataset.sig = sig;
    box.innerHTML = items.map(([cls, text, btns]) => `<div class="banner ${cls}"><div>${text}</div><div class="row">${btns}</div></div>`).join('');
    box.querySelectorAll('[data-act]').forEach((b) => b.addEventListener('click', () => {
      const a = b.dataset.act;
      if (a === 'setup') startSetup();
      if (a === 'signin') api('/api/linkedin', {}).catch((e) => toast(e.message, true));
      if (a === 'answers') go('info');
      if (a === 'drive') api('/api/drive/connect', {}).catch((e) => toast(e.message, true));
      if (a === 'acc-check') accCheck(b);
      if (a === 'update') { go('settings'); setTimeout(() => $('update-card').scrollIntoView({ behavior: 'smooth' }), 300); }
      if (a === 'install-now') api('/api/update/install', {}).then(() => toast('Installing the update…')).catch((e) => toast(e.message, true));
    }));
    box.querySelectorAll('[data-perm]').forEach((b) => b.addEventListener('click',
      () => api('/api/permission', { which: b.dataset.perm }).catch((e) => toast(e.message, true))));
  }

  async function accCheck(btn) {
    if (btn) btn.disabled = true;
    try {
      const r = await api('/api/account/refresh', {});
      toast(r.account.pro ? (auto() ? 'Pro is on: press Start.' : 'Pro is on.') : 'No Pro yet: auto apply waits for it.');
    } catch (e) { toast(e.message, true); }
    if (btn) btn.disabled = false;
    refresh();
  }

  function jobLink(j) {
    return `<a href="${esc(j.url)}" target="_blank" rel="noopener"><b>${esc(j.title)}</b></a>`
      + `${j.company ? ` <span class="muted">at ${esc(j.company)}</span>` : ''}`;
  }
  function whyText(reason) {
    const r = String(reason || '');
    if (r.includes('Form error:')) {
      return r.split('Form error:')[1].split(' / ').map((p) => {
        const i = p.lastIndexOf(': ');
        return i > 0 ? `<b>${esc(p.slice(0, i).trim())}</b> ${esc(p.slice(i + 2))}` : esc(p.trim());
      }).join('<br>');
    }
    return esc(r.split(' | ').pop());
  }
  function section(cls, title, items, render) {
    if (!items.length) return '';
    return `<div class="brief-sec ${cls}"><h3>${title}<span class="n">${items.length}</span></h3>`
      + `<ul class="brief-list">${items.map(render).join('')}</ul></div>`;
  }
  let lastBriefKey = '';
  function renderBrief(sum, live) {
    const box = $('brief');
    $('brief-title').textContent = live ? 'This session' : 'Last session';
    if (!sum || !sum.total) {
      $('brief-time').textContent = sum && sum.started ? fmtWhen(sum.started) : '';
      let msg = 'When a session ends you get a summary here: what was sent, what got stuck and why.';
      if (live) msg = 'Nothing finished yet. Every application shows up here as it happens.';
      else if (sum) msg = 'The last session found no new jobs. Add more job titles, or choose a longer “Posted within” in Settings.';
      box.innerHTML = `<p class="empty">${msg}</p>`;
      lastBriefKey = '';
      return;
    }
    const end = sum.ended || Date.now() / 1000;
    $('brief-time').textContent = `${fmtWhen(sum.started)} to ${sum.ended ? fmtWhen(sum.ended) : 'now'}, ${fmtDur(end - sum.started)}`;
    const questions = sum.questions || [];
    const readyNow = sum.ready || [];
    const key = JSON.stringify([sum.total, sum.applied.length, sum.stuck.length, sum.errors.length, sum.filled.length,
      questions.length, sum.ended, readyNow.length]);
    if (key === lastBriefKey) return;
    lastBriefKey = key;
    let html = '';
    html += section('good', 'Sent', sum.applied, (j) => `<li>${jobLink(j)}</li>`);
    html += section('warn', 'Ready to send: waiting for your yes', readyNow,
      (j) => `<li>${jobLink(j)}${j.score != null ? ` <span class="tag info">${j.score}/10</span>` : ''}</li>`);
    html += section('good', sum.mode === 'dry' ? 'Good matches' : 'Filled, not sent (test run)', sum.filled,
      (j) => `<li>${jobLink(j)}${j.score != null ? ` <span class="tag info">${j.score}/10</span>` : ''}</li>`);
    html += section('warn', 'Stuck: form not sent', sum.stuck, (j) => `<li>${jobLink(j)}<span class="why">${whyText(j.reason)}</span></li>`);
    html += section('bad', 'Problems', sum.errors, (j) => `<li>${jobLink(j)}<span class="why">${esc(String(j.reason).split(' | ').pop().slice(0, 200))}</span></li>`);
    html += section('warn', 'Waiting for your answer', questions, (j) => `<li>${jobLink(j)}<span class="why">${
      esc(String(j.reason).split('Waiting for your answer: ').pop())}</span></li>`);
    if (sum.skipped_total) {
      html += `<div class="brief-sec"><h3>Skipped<span class="n">${sum.skipped_total}</span></h3><div class="chipline">`
        + Object.entries(sum.skipped).map(([k, v]) => `<span>${esc(k)}: ${v}</span>`).join('') + '</div></div>';
    }
    if ((sum.stuck.length || sum.errors.length) && !live) {
      html += '<div class="brief-sec"><button class="btn wide" id="btn-retry" type="button">Try the jobs with problems again</button></div>';
    }
    if (!sum.applied.length && !sum.filled.length && !sum.stuck.length && !sum.errors.length && !questions.length
      && !readyNow.length) {
      html = `<p class="muted">Nothing sent in this session yet.</p>${html}`;
    }
    box.innerHTML = html;
    const retry = $('btn-retry');
    if (retry) retry.addEventListener('click', () => startBot('apply', { retry: true }));
  }
  // ------------------------------------------------------------------ ready to send: your yes first
  let readySig = '';
  let readyItems = [];
  async function loadReady() {
    try {
      const r = await api('/api/ready');
      readyItems = r.items || [];
    } catch (e) { return; }
    renderReady();
  }
  function renderReady() {
    const waiting = readyItems.filter((j) => j.status === 'ready');
    $('ready-panel').hidden = !readyItems.length;
    $('ready-n').hidden = !waiting.length;
    $('ready-n').textContent = waiting.length;
    $('ready-apply-all').hidden = waiting.length < 2;
    $('ready-apply-all').textContent = `Apply all ${waiting.length}`;
    const later = state && state.running ? 'Sending soon' : 'Sends at the next start';
    $('ready-list').innerHTML = readyItems.map((j) => `<li data-id="${esc(j.id)}"><a class="job" href="${esc(j.url)}" target="_blank" rel="noopener">${
      esc(j.title || 'A job')}</a><span class="co">${esc(j.company || '')}${j.score != null ? `${j.company ? ' · ' : ''}match ${esc(j.score)}/10` : ''}</span>${
      j.status === 'approved' ? `<span class="tag good ready-tag">${later}</span>`
        : '<div class="ready-btns"><button class="btn" data-ready="skip" type="button">Skip</button>'
          + '<button class="btn primary" data-ready="approve" type="button">Apply</button></div>'}</li>`).join('');
  }
  async function readyAct(op, ids, btn) {
    if (btn) btn.disabled = true;
    try {
      const r = await api('/api/ready', ids ? { op, ids } : { op, all: true });
      readyItems = r.items || [];
      renderReady();
      if (!r.count) toast('Already done.');
      else if (op === 'skip') toast('Skipped. It will not be sent.');
      else toast(r.count === 1 ? 'Sending it.' : `Sending ${r.count} applications, one by one.`);
    } catch (e) {
      toast(e.message, true);
      if (btn) btn.disabled = false;
    }
    refresh();
  }
  $('ready-list').addEventListener('click', (e) => {
    const b = e.target.closest('[data-ready]');
    if (b) readyAct(b.dataset.ready, [b.closest('li').dataset.id], b);
  });
  $('ready-apply-all').addEventListener('click', (e) => readyAct('approve', null, e.currentTarget));
  window.aaShowReady = () => {
    go('home');
    loadReady().then(() => {
      if (!$('ready-panel').hidden) $('ready-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  };

  let lastRecentSig = '';
  function renderRecent(list) {
    const sig = JSON.stringify(list.map((j) => j.id));
    if (sig === lastRecentSig) return;
    lastRecentSig = sig;
    $('recent-panel').hidden = !list.length;
    $('recent').innerHTML = list.map((j) => `<li><a class="job" href="${esc(j.url)}" target="_blank" rel="noopener">${
      esc(j.title)}</a><span class="co">${esc(j.company || '')}</span><span class="why">${esc(fmtWhen(j.ts))}</span></li>`).join('');
  }

  async function startBot(mode, extra = {}) {
    if (state && state.setup.account && !acc().pro) {
      try { state.account = (await api('/api/account/refresh', {})).account; } catch (e) { /* use what is known */ }
      if (!acc().pro) {
        toast('Auto apply needs Pro on your account.', true);
        refresh();
        return;
      }
    }
    primeAlerts();
    if (state && state.permissions && !state.permissions.notifications) {
      api('/api/permission', { which: 'notifications' }).catch(() => {});
    }
    try {
      await api('/api/start', { mode, once: false, retry: !!extra.retry });
      toast(mode === 'apply' ? 'Started. You can lock the phone.' : 'Test run started. Forms are filled but not sent.');
    } catch (e) {
      toast(e.message, true);
    }
    refresh();
  }
  $('btn-start').addEventListener('click', async () => {
    $('btn-start').disabled = true;
    await startBot('apply');
    setTimeout(() => { $('btn-start').disabled = false; }, 800);
  });
  $('btn-test').addEventListener('click', () => startBot('test'));
  $('btn-stop').addEventListener('click', async () => {
    $('btn-stop').disabled = true;
    try { await api('/api/stop', {}); toast('Stopping after the current step…'); } catch (e) { toast(e.message, true); }
    refresh();
  });
  $('btn-live').addEventListener('click', () => { if (window.AA && window.AA.liveView) window.AA.liveView(); });

  // ------------------------------------------------------------------ activity log
  let logSeq = 0;
  const LOG_MAX = 900;
  function classify(text) {
    if (/^\s*APPLIED\b|^\s*\+ /.test(text)) return 'ok';
    if (/^SESSION BRIEF|^=+$/.test(text)) return 'head';
    if (/Question for you:/.test(text)) return 'q';
    if (/Your answer:|Saved your answer|From your setup:/.test(text)) return 'info';
    if (/\bError:|Unexpected error|refused|blocks openrouter/i.test(text)) return 'bad';
    if (/Stuck:|LinkedIn rejected|Fixing '|Tip:|Daily limit|Please sign in|Tapped|^\s*! /.test(text)) return 'warn';
    if (/Match \d+\/10|Searching "|Form filled|Next application slot/.test(text)) return 'info';
    if (/Skipped:|AI answer:/.test(text)) return 'dim';
    return '';
  }
  function appendLog(lines) {
    if (!lines.length) return;
    const box = $('log');
    const frag = document.createDocumentFragment();
    for (const l of lines) {
      if (/^=+$/.test(l.text)) {
        const sep = document.createElement('div');
        sep.className = 'sep';
        frag.appendChild(sep);
        continue;
      }
      const div = document.createElement('div');
      const t = document.createElement('span');
      t.className = 't';
      t.textContent = l.t;
      const m = document.createElement('span');
      m.className = classify(l.text);
      m.textContent = l.text;
      div.append(t, m);
      frag.appendChild(div);
    }
    box.appendChild(frag);
    while (box.childElementCount > LOG_MAX) box.firstElementChild.remove();
    if (view === 'log' && $('autoscroll').checked) window.scrollTo(0, document.body.scrollHeight);
  }
  async function logLoop() {
    try {
      const d = await api(`/api/log?since=${logSeq}`);
      appendLog(d.lines);
      logSeq = d.next;
    } catch (e) { /* app closed */ }
    setTimeout(logLoop, document.hidden ? 20000 : 1000);
  }

  // ------------------------------------------------------------------ questions for you
  const KIND_HELP = {
    numeric: 'LinkedIn wants a number here.', choice: 'Pick one.', multi: 'Tick all that apply.',
    textarea: 'A few sentences, usually written for this job, so it is not kept unless you tick the box.', text: '',
  };
  const shown = (v) => (Array.isArray(v) ? v.join(', ') : String(v == null ? '' : v));

  function makeAnswer(box, item, submit) {
    const opts = item.options || [];
    const multi = item.kind === 'multi';
    const w = { focus() {} };
    if (opts.length && (multi || opts.length <= 8)) {
      const picked = new Set();
      box.innerHTML = `<div class="q-options" role="${multi ? 'group' : 'radiogroup'}">${opts.map((o, i) => `<button type="button"
        class="q-opt${multi ? ' multi' : ''}" role="${multi ? 'checkbox' : 'radio'}" aria-checked="false" data-i="${i}">
        <span class="box"></span><span>${esc(o)}</span></button>`).join('')}</div>`;
      const btns = [...box.querySelectorAll('.q-opt')];
      const paint = () => btns.forEach((b) => {
        const on = picked.has(opts[+b.dataset.i]);
        b.classList.toggle('on', on);
        b.setAttribute('aria-checked', on ? 'true' : 'false');
      });
      const toggle = (o) => {
        if (multi) { if (picked.has(o)) picked.delete(o); else picked.add(o); } else { picked.clear(); picked.add(o); }
        paint();
      };
      btns.forEach((b) => b.addEventListener('click', () => toggle(opts[+b.dataset.i])));
      w.set = (v) => (Array.isArray(v) ? v : [v]).forEach((x) => { if (opts.includes(x)) toggle(x); });
      w.value = () => (multi ? [...picked] : ([...picked][0] || ''));
      w.markAI = (vals) => btns.forEach((b) => {
        const has = (vals || []).includes(opts[+b.dataset.i]);
        const tag = b.querySelector('.ai');
        if (has && !tag) b.insertAdjacentHTML('beforeend', '<span class="ai">Suggested</span>');
        if (!has && tag) tag.remove();
      });
    } else if (opts.length) {
      box.innerHTML = `<select aria-label="Answer"><option value="">Choose…</option>${
        opts.map((o) => `<option value="${esc(o)}">${esc(o)}</option>`).join('')}</select>`;
      const sel = box.querySelector('select');
      w.set = (v) => { sel.value = Array.isArray(v) ? v[0] : v; };
      w.value = () => sel.value;
      w.markAI = (vals) => [...sel.options].forEach((o) => {
        if (o.value) o.textContent = (vals || []).includes(o.value) ? `${o.value} (suggested)` : o.value;
      });
    } else {
      const long = item.kind === 'textarea';
      const num = item.kind === 'numeric';
      const max = item.maxlen ? ` maxlength="${item.maxlen}"` : '';
      const ph = esc(item.placeholder || (num ? 'A number, like 2' : 'Type your answer'));
      box.innerHTML = long ? `<textarea rows="5"${max} placeholder="${ph}" aria-label="Answer"></textarea>`
        : `<input type="text"${num ? ' inputmode="decimal"' : ''}${max} placeholder="${ph}" aria-label="Answer" autocomplete="off">`;
      if (item.maxlen) box.insertAdjacentHTML('beforeend', '<span class="counter"></span>');
      const el = box.querySelector(long ? 'textarea' : 'input');
      const counter = box.querySelector('.counter');
      const count = () => { if (counter) counter.textContent = `${el.value.length}/${item.maxlen}`; };
      el.addEventListener('input', count);
      el.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !long) { e.preventDefault(); submit(); } });
      count();
      w.set = (v) => { el.value = shown(v); count(); };
      w.value = () => el.value.trim();
      w.markAI = () => {};
      w.focus = () => el.focus();
    }
    return w;
  }
  function renderChips(box, item, w) {
    const sug = item.suggestions || [];
    let html = sug.map((v, i) => {
      const t = shown(v);
      const long = t.length > 70;
      return `<button type="button" class="q-chip${long ? ' long' : ''}" data-i="${i}">${esc(long && t.length > 220 ? `${t.slice(0, 220)}…` : t)}</button>`;
    }).join('');
    if (item.loading) html += '<span class="loading">Finding answers in your CV…</span>';
    else if (!sug.length) html = '<span class="muted small">No suggestions this time. Type your answer.</span>';
    box.innerHTML = html;
    box.querySelectorAll('.q-chip').forEach((b) => b.addEventListener('click', () => w.set(sug[+b.dataset.i])));
    w.markAI(item.kind === 'multi' ? sug : sug.slice(0, 1));
  }

  let qItem = null;
  let qWidget = null;
  let qSig = '';
  let qTimerAt = 0;
  const qDone = new Set();
  function hideQuestion() {
    $('qcard').hidden = true;
    qItem = null;
    qWidget = null;
    qSig = '';
    renderQuestionBar();
  }
  function qError(msg) {
    $('q-err').textContent = msg;
    $('q-err').hidden = !msg;
  }
  function renderQuestionBar() {
    $('q-global').hidden = !(qItem && view !== 'home' && view !== 'setup');
    if (qItem) $('q-global-text').textContent = qItem.question;
  }
  $('q-global').addEventListener('click', () => go('home'));
  function renderQuestion(q) {
    if (!q || qDone.has(q.id)) {
      if (qItem) hideQuestion();
      return;
    }
    if (!qItem || qItem.id !== q.id) {
      qItem = q;
      $('qcard').hidden = false;
      const job = q.job || {};
      $('q-job').hidden = !job.title;
      $('q-job').href = job.url || '#';
      $('q-job').innerHTML = `For <b>${esc(job.title)}</b>${job.company ? ` at ${esc(job.company)}` : ''}`;
      $('q-text').textContent = q.question;
      $('q-kind').textContent = [q.note || '', KIND_HELP[q.kind] || '', q.maxlen ? `Up to ${q.maxlen} characters.` : '']
        .join(' ').trim();
      const rej = (q.rejected || []).map((r) => `“${r}”`).join(', ');
      $('q-rejected').hidden = !q.hint;
      $('q-rejected').textContent = q.hint ? `LinkedIn did not accept ${rej || 'the answer'}: ${q.hint}` : '';
      qWidget = makeAnswer($('q-input'), q, sendAnswer);
      $('q-remember').checked = q.remember !== false;
      const sim = q.similar || [];
      $('q-similar').hidden = !sim.length;
      $('q-similar').innerHTML = sim.length ? `<span class="q-label">You answered similar questions</span>${sim.map((x, i) =>
        `<button type="button" class="q-sim" data-i="${i}"><span>${esc(x.question)}</span><b>${esc(shown(x.answer))}</b></button>`).join('')}` : '';
      $('q-similar').querySelectorAll('.q-sim').forEach((b) => b.addEventListener('click', () => qWidget.set(sim[+b.dataset.i].answer)));
      qError('');
      $('q-send').disabled = false;
      $('q-skip').disabled = false;
      qSig = '';
      beep();
      if (view === 'home') window.scrollTo({ top: 0, behavior: 'smooth' });
    }
    const sig = JSON.stringify([q.loading, q.suggestions, q.reason]);
    if (sig !== qSig) {
      qSig = sig;
      renderChips($('q-chips'), q, qWidget);
      $('q-reason').textContent = q.reason || '';
    }
    qTimerAt = q.timeout_at ? q.timeout_at - state.now + Date.now() / 1000 : 0;
    renderQuestionBar();
    qTick();
  }
  function qTick() {
    if (!qItem) return;
    const el = $('q-timer');
    if (!qTimerAt) { el.textContent = 'The form waits for you'; el.className = 'q-timer'; return; }
    const left = qTimerAt - Date.now() / 1000;
    el.textContent = left > 0 ? `Skips this job for now in ${fmtDur(left)}` : 'Skipping this job for now…';
    el.className = `q-timer${left < 60 ? ' urgent' : ''}`;
  }
  setInterval(qTick, 1000);
  async function sendAnswer() {
    if (!qItem || !qWidget) return;
    const answer = qWidget.value();
    if (!answer || (Array.isArray(answer) && !answer.length)) {
      qError((qItem.options || []).length ? 'Pick an answer first.' : 'Type an answer or tap a suggestion first.');
      return;
    }
    $('q-send').disabled = true;
    try {
      await api('/api/answer', { id: qItem.id, answer, remember: $('q-remember').checked });
      qDone.add(qItem.id);
      hideQuestion();
      toast('Answer sent. The form continues.');
    } catch (e) {
      qError(e.message);
      $('q-send').disabled = false;
    }
    refresh();
  }
  $('q-send').addEventListener('click', sendAnswer);
  $('q-skip').addEventListener('click', async () => {
    if (!qItem) return;
    $('q-skip').disabled = true;
    try {
      await api('/api/skip-question', { id: qItem.id });
      qDone.add(qItem.id);
      hideQuestion();
      toast('Skipped this job.');
    } catch (e) {
      qError(e.message);
      $('q-skip').disabled = false;
    }
    refresh();
  });

  let audioCtx = null;
  function primeAlerts() {
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { /* no audio */ }
  }
  document.addEventListener('pointerdown', () => primeAlerts(), { once: true });
  function beep() {
    if (!audioCtx || audioCtx.state !== 'running') return;
    const t = audioCtx.currentTime;
    [[660, 0], [880, 0.17]].forEach(([freq, delay]) => {
      const o = audioCtx.createOscillator();
      const g = audioCtx.createGain();
      o.type = 'sine';
      o.frequency.value = freq;
      g.gain.setValueAtTime(0.0001, t + delay);
      g.gain.exponentialRampToValueAtTime(0.2, t + delay + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + delay + 0.15);
      o.connect(g).connect(audioCtx.destination);
      o.start(t + delay);
      o.stop(t + delay + 0.17);
    });
  }

  let lastHere = 0;
  function here() {
    if (Date.now() - lastHere < 30000) return;
    lastHere = Date.now();
    api('/api/here', {}).catch(() => {});
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { here(); refresh(); } });
  document.addEventListener('pointerdown', here);

  // ------------------------------------------------------------------ settings
  const LIST_KEYS = ['SKIP_COMPANIES', 'SKIP_TITLE_WORDS'];
  const PLAIN_KEYS = ['LOCATION', 'DATE_POSTED', 'PER_HOUR', 'PER_DAY', 'MIN_SCORE', 'ASK_TIMEOUT_MIN', 'UPDATE_SOURCE', 'ABOUT_ME',
    'DAILY_GOAL', 'SEARCH_EVERY_MIN'];
  let settingsInfoAsked = false;
  async function loadSettings() {
    try {
      const d = await api('/api/settings');
      // A field you changed keeps your value until it is saved (the settings are read again on each screen).
      for (const k of PLAIN_KEYS) { const el = $(`s-${k}`); if (el && !el.dataset.dirty) el.value = d[k] == null ? '' : d[k]; }
      for (const k of LIST_KEYS) {
        if (!$(`s-${k}`).dataset.dirty) $(`s-${k}`).value = String(d[k] || '').split(';').map((x) => x.trim()).filter(Boolean).join('\n');
      }
      sTitleEditor.set(String(d.KEYWORDS || '').split(';').map((x) => x.trim()).filter(Boolean));
      document.querySelectorAll('.chips[data-key]').forEach((box) => {
        if (box.dataset.dirty) return;
        const vals = String(d[box.dataset.key] || '').toLowerCase().split(/[,;\s]+/);
        box.querySelectorAll('input').forEach((i) => { i.checked = vals.includes(i.value); });
      });
      $('s-AUTO_UPDATE').checked = String(d.AUTO_UPDATE) !== '0';
      $('s-APPROVE_FIRST').checked = String(d.APPROVE_FIRST) !== '0';
      const nq = String(d.NEW_QUESTIONS || '').trim().toLowerCase();
      const newq = ['unsure', 'sure', 'mixed'].includes(nq) ? 'unsure'
        : ['ai', 'auto', 'off', 'no', 'false', '0'].includes(nq) ? 'ai' : 'ask';
      document.querySelectorAll('input[name=newq]').forEach((r) => { r.checked = r.value === newq; });
      if (!$('s-DATE_POSTED').value) $('s-DATE_POSTED').value = 'week';
      $('min-score-val').textContent = `${$('s-MIN_SCORE').value}/10`;
      settingsLive();
      if (!settingsInfoAsked && acc().signed_in && !acc().google) {   // does the server offer Sign in with Google?
        settingsInfoAsked = true;
        api('/api/account/info', {}).then((r) => { if (state) state.account = r.account; renderAccountCard(); }).catch(() => {});
      }
    } catch (e) { toast(e.message, true); }
  }
  function settingsLive() {
    if (!state) return;
    const s = state.setup;
    $('settings-running').hidden = !state.running;
    renderAccountCard();
    renderDataCard();
    $('li-state').textContent = s.signed_in ? 'Signed in' : 'Not signed in';
    cvStates();
  }
  $('s-MIN_SCORE').addEventListener('input', () => { $('min-score-val').textContent = `${$('s-MIN_SCORE').value}/10`; });
  ['input', 'change'].forEach((ev) => document.addEventListener(ev, (e) => {
    const t = e.target;
    if (!t || !t.closest) return;
    const chips = t.closest('.chips[data-key]');
    if (chips) chips.dataset.dirty = '1';
    else if (t.id && /^s-[A-Z_]+$/.test(t.id)) t.dataset.dirty = '1';
  }));
  document.querySelectorAll('.save-settings').forEach((btn) => btn.addEventListener('click', async () => {
    const body = {};
    for (const k of PLAIN_KEYS) { const el = $(`s-${k}`); if (el) body[k] = el.value; }
    for (const k of LIST_KEYS) body[k] = $(`s-${k}`).value;
    body.KEYWORDS = sTitleEditor.get().join('\n');
    document.querySelectorAll('.chips[data-key]').forEach((box) => {
      body[box.dataset.key] = [...box.querySelectorAll('input:checked')].map((i) => i.value).join(',');
    });
    const newq = document.querySelector('input[name=newq]:checked');
    if (newq) body.NEW_QUESTIONS = newq.value;
    try {
      const r = await api('/api/settings', body);
      document.querySelectorAll('[data-dirty]').forEach((x) => { delete x.dataset.dirty; });
      sTitleEditor.clean();
      toast(r.running ? 'Saved. The question setting applies now, the rest from the next start.' : 'Saved.');
      refresh();
      loadQueue(true);  // new searches for new job titles
    } catch (e) { toast(e.message, true); }
  }));
  $('btn-save-answers').addEventListener('click', async () => {
    try { if (await saveProfile($('s-answers'))) { paintMainCount(); loadFacts(); } } catch (e) { toast(e.message, true); }
  });
  function renderAccountCard() {
    const a = acc();
    $('acc-state').textContent = !a.signed_in ? 'Signed out' : a.pro ? 'Pro' : 'Free';
    $('acc-state').className = `tag ${!a.signed_in ? '' : a.pro ? 'good' : 'info'}`;
    $('acc-who').innerHTML = a.signed_in ? `${esc(a.name || a.email)}<small>${esc(a.name ? a.email : '')}${a.google
      ? `${a.name ? ' · ' : ''}Signed in with Google` : ''}</small>` : 'Not signed in';
    const byEmail = !a.has_password;
    $('acc-del-pw-label').textContent = byEmail ? 'Type your email to confirm' : 'Your password';
    $('acc-del-pw').type = byEmail ? 'email' : 'password';
    $('acc-del-pw').autocomplete = byEmail ? 'off' : 'current-password';
    $('acc-pro').textContent = !auto() ? (!a.signed_in ? 'Sign in to use the AI that fills forms and writes cover letters.'
      : 'AI help is on: forms, new questions and cover letters.')
      : !a.signed_in ? 'Sign in to use the AI and start auto apply.'
        : a.pro ? `Auto apply is active.${a.max_per_day ? ` At most ${a.max_per_day} applications a day.` : ''}`
          : 'Free account: forms, cover letters and the AI work. Auto apply and its notifications need Pro.';
    $('btn-acc-check').hidden = !a.signed_in;
    $('btn-acc-out').hidden = !a.signed_in;
    $('btn-acc-signin').hidden = !!a.signed_in;
    $('btn-acc-google').hidden = !(a.signed_in && !a.google && a.google_signin);
    $('acc-del-box').hidden = !a.signed_in;
    $('acc-server-box').hidden = !!a.server_built_in;
    if (!a.server_built_in && document.activeElement !== $('s-server')) $('s-server').value = (a.server || '').replace(/^https:\/\//, '');
    const priv = a.server ? `${a.server}/privacy` : '';
    $('privacy-link').hidden = !priv;
    if (priv) $('privacy-link').href = priv;
  }
  $('btn-acc-google').addEventListener('click', async () => {
    try {
      await api('/api/account/google-link', {});
      googleLinking = true;
      toast('Pick your Google account in the browser, then come back here.');
    } catch (e) { toast(e.message, true); }
  });
  $('btn-acc-check').addEventListener('click', async () => {
    $('acc-out').textContent = 'Checking…';
    try {
      const r = await api('/api/test-ai', {});
      $('acc-out').textContent = r.message;
    } catch (e) { $('acc-out').textContent = e.message; }
    refresh();
  });
  $('btn-acc-signin').addEventListener('click', () => startSetup('account'));
  $('btn-acc-out').addEventListener('click', async () => {
    try { await api('/api/account/logout', {}); toast('Signed out.'); } catch (e) { toast(e.message, true); }
    $('acc-out').textContent = '';
    refresh();
  });
  $('btn-server').addEventListener('click', async () => {
    try {
      await api('/api/account/server', { server: $('s-server').value.trim() });
      accInfoAsked = false;
      toast('Saved. Sign in on this server.');
    } catch (e) { toast(e.message, true); }
    refresh();
  });
  $('btn-acc-del').addEventListener('click', async () => {
    const pw = $('acc-del-pw').value;
    const byEmail = !acc().has_password;
    if (!pw) { toast(byEmail ? 'Type your email first.' : 'Type your password first.', true); return; }
    $('btn-acc-del').disabled = true;
    try {
      await api('/api/account/delete', byEmail ? { confirm: pw.trim() } : { password: pw });
      $('acc-del-pw').value = '';
      $('acc-del-box').open = false;
      toast('Your account was deleted.');
    } catch (e) { toast(e.message, true); }
    $('btn-acc-del').disabled = false;
    refresh();
  });
  // ------------------------------------------------------------------ your data, and Google Drive
  let driveInfo = null;
  let driveBusy = false;
  let driveAskedAt = 0;
  async function loadDrive(fresh) {
    if (!acc().signed_in) { driveInfo = null; renderDataCard(); return; }
    driveAskedAt = Date.now();
    const was = JSON.stringify([driveInfo && driveInfo.connected, driveInfo && driveInfo.reconnect]);
    try {
      const r = await api(`/api/sync${fresh ? '?fresh=1' : ''}`);
      driveInfo = r.drive || null;
    } catch (e) { /* shown as unknown */ }
    renderDataCard();
    if (JSON.stringify([driveInfo && driveInfo.connected, driveInfo && driveInfo.reconnect]) !== was) refresh();  // Home's banner
  }
  function renderDataCard() {
    if (!state) return;
    const a = acc();
    const sy = state.sync || {};
    const tag = $('sync-state');
    $('drive-box').hidden = !a.signed_in;
    if (!a.signed_in) {
      tag.textContent = '';
      tag.className = 'tag';
      $('sync-line').textContent = 'Sign in so your CV, answers and jobs are kept in your account, for every phone you sign in on.';
      return;
    }
    const bad = !!sy.error;
    tag.textContent = sy.busy ? 'Syncing…' : bad ? 'Not synced' : sy.at ? 'Synced' : 'Waiting';
    tag.className = `tag ${sy.busy ? 'info' : bad ? 'warn' : sy.at ? 'good' : ''}`;
    $('sync-line').innerHTML = bad ? `${esc(sy.error)}` : sy.at
      ? `Your CV, answers and jobs are kept in your account, so every phone you sign in on has them. Last synced ${esc(agoText(sy.at))}.`
        + (sy.waiting ? ` ${plural(sy.waiting, 'change')} still to send.` : '')
      : 'Your CV, answers and jobs are kept in your account, so every phone you sign in on has them.';
    $('sync-line').classList.toggle('bad-text', bad);
    const d = driveInfo || sy.drive || null;
    const st = $('drive-state');
    const on = !!(d && d.connected);
    const ready = on && d.ready;
    const reconnect = on && d.reconnect;
    $('drive-who').textContent = on ? (d.email ? `Connected as ${d.email}` : 'Connected') : 'Not connected';
    st.textContent = !d ? '' : reconnect ? 'Connect again' : on ? (d.error ? 'Problem' : ready ? 'On' : 'Setting up') : '';
    st.className = `tag ${reconnect || (on && d.error) ? 'warn' : ready ? 'good' : on ? 'info' : ''}`;
    let text;
    if (!d) text = 'Checking…';
    else if (!d.available && !on) text = d.error && !/not have Google Drive/.test(d.error) ? d.error
      : 'Google Drive isn’t set up on the server yet.';
    else if (!on) text = 'Keep your CV, your answers and your jobs in a JSTB folder in your own Google Drive too, and edit them there. The app sees only the files it makes, nothing else in your Drive.';
    else if (reconnect) text = 'Google asks you to connect again (the access was removed or ran out). Your files are still in your Drive.';
    else if (d.error) text = d.error;
    else if (!ready) text = 'Making your JSTB folder: your CV, My answers and My jobs.';
    else text = `In your JSTB folder. Synced ${agoText(d.synced_at)}.`;
    $('drive-text').textContent = text;
    $('drive-text').classList.toggle('bad-text', !!(on && d.error));
    $('drive-files').hidden = !ready;
    $('drive-cv-name').textContent = state.setup.cv_name || 'The file the app uses';
    $('btn-drive-connect').hidden = on && !reconnect;
    $('btn-drive-connect').textContent = reconnect ? 'Connect again' : 'Connect Google Drive';
    $('btn-drive-connect').disabled = !d || (!d.available && !on) || driveBusy;
    $('btn-drive-open').hidden = !ready;
    $('btn-drive-sync').hidden = !on || reconnect;
    $('btn-drive-sync').disabled = driveBusy;
    $('btn-drive-sync').textContent = driveBusy ? 'Syncing…' : 'Sync now';
    $('drive-help').hidden = !on;
    $('drive-off-box').hidden = !on;
  }
  function agoText(ts) {
    if (!ts) return 'never';
    const s = Math.max(0, Date.now() / 1000 - ts);
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return new Date(ts * 1000).toLocaleDateString();
  }
  $('btn-drive-connect').addEventListener('click', async () => {
    try {
      await api('/api/drive/connect', {});
      toast('Choose your Google account in the browser, allow JSTB, then come back here.');
      driveAskedAt = 0;
    } catch (e) { toast(e.message, true); }
  });
  async function driveSync(quiet) {
    if (driveBusy) return;
    driveBusy = true;
    renderDataCard();
    try {
      const r = await api('/api/drive/sync', {});
      driveInfo = r.drive || driveInfo;
      if (!quiet) toast(driveInfo && driveInfo.error ? driveInfo.error : 'Synced with your Google Drive.', !!(driveInfo && driveInfo.error));
      if (((r.sync || {}).note || '').includes('from your account')) { loadQueue(true); loadTracker(true); }
    } catch (e) { if (!quiet) toast(e.message, true); }
    driveBusy = false;
    await refresh();
    renderDataCard();
  }
  $('btn-drive-sync').addEventListener('click', () => driveSync(false));
  document.querySelectorAll('[data-open]').forEach((b) => b.addEventListener('click', () => {
    api('/api/drive/open', { which: b.dataset.open }).catch((e) => toast(e.message, true));
  }));
  $('btn-drive-off').addEventListener('click', async () => {
    try {
      const r = await api('/api/drive/disconnect', {});
      driveInfo = r.drive || null;
      $('drive-off-box').open = false;
      toast('Google Drive disconnected. The files stay in your Drive.');
    } catch (e) { toast(e.message, true); }
    renderDataCard();
  });
  // Back in the app (from Google's page, or from editing a sheet): what changed comes in.
  function driveResumed() {
    const d = driveInfo || (state && state.sync && state.sync.drive);
    if (!acc().signed_in) return;
    if (!d || !d.connected || d.reconnect) loadDrive(true);   // maybe just connected on Google's page
    else if (Date.now() - driveAskedAt > 60000) {            // maybe edited in Drive meanwhile
      driveAskedAt = Date.now();
      driveSync(true);
    }
  }

  $('btn-rerun').addEventListener('click', async () => {
    wJobsFilled = false;
    await api('/api/setup', { done: false }).catch(() => {});
    await refresh();
    startSetup('welcome');
  });

  // ------------------------------------------------------------------ app updates
  const UPDATE_BUSY = ['downloading', 'installing', 'confirm'];
  function renderUpdate(u, running) {
    if (!u) return;
    const cur = (u.current && u.current.name) || '';
    $('help-version').textContent = cur ? `Version ${cur}${state && state.build && state.build.flavor === 'play' ? '' : ' (GitHub)'}.` : '';
    $('update-card').hidden = !!u.disabled;
    if (u.disabled) return;
    $('app-version').textContent = cur ? `Version ${cur}` : '';
    const last = u.last || null;
    const dl = u.download || {};
    const st = dl.state || 'idle';
    let status = '';
    if (u.checking) status = 'Checking for updates…';
    else if (st !== 'idle' && dl.message) status = dl.message;
    else if (last) status = last.message || '';
    $('update-status').textContent = status;
    $('update-status').className = `hint${st === 'error' || (last && last.ok === false && st === 'idle') ? ' bad-text' : ''}`;
    const avail = !!(last && last.available);
    $('update-box').hidden = !avail;
    if (avail) {
      $('update-version').textContent = `Version ${last.version}`;
      $('update-notes').textContent = last.notes || '';
      $('update-notes').hidden = !last.notes;
    }
    $('update-meter-wrap').hidden = st !== 'downloading';
    $('update-meter').style.width = `${dl.progress || 0}%`;
    const busyNow = UPDATE_BUSY.includes(st);
    const btn = $('btn-update-install');
    btn.hidden = !avail;
    btn.disabled = busyNow || running;
    btn.textContent = running ? 'Stop the app to update' : busyNow ? 'Updating…' : st === 'ready' ? 'Install now' : 'Download and install';
    $('btn-update-check').disabled = !!u.checking || busyNow;
  }
  $('s-APPROVE_FIRST').addEventListener('change', async () => {
    const on = $('s-APPROVE_FIRST').checked;
    try {
      await api('/api/settings', { APPROVE_FIRST: on ? '1' : '0' });
      delete $('s-APPROVE_FIRST').dataset.dirty;
      toast(on ? 'Each application waits for your yes.' : 'Applications are sent by themselves.');
    } catch (e) { toast(e.message, true); }
  });
  $('s-AUTO_UPDATE').addEventListener('change', async () => {
    const on = $('s-AUTO_UPDATE').checked;
    try {
      await api('/api/settings', { AUTO_UPDATE: on ? '1' : '0' });
      toast(on ? 'New versions install by themselves.' : 'Updates wait for you to install them.');
    } catch (e) { toast(e.message, true); }
  });
  $('btn-update-check').addEventListener('click', async () => {
    try { await api('/api/update/check', {}); } catch (e) { toast(e.message, true); }
    refresh();
  });
  $('btn-update-install').addEventListener('click', async () => {
    try { await api('/api/update/install', {}); toast('Downloading the update…'); } catch (e) { toast(e.message, true); }
    refresh();
  });

  // ------------------------------------------------------------------ saved answers
  let openItems = [];
  let lastOpenCount = -1;
  async function loadOpen() {
    try {
      openItems = (await api('/api/open-questions')).items || [];
      renderOpen();
    } catch (e) { toast(e.message, true); }
  }
  function renderOpen() {
    $('open-card').hidden = !openItems.length;
    $('open-count').textContent = openItems.length;
    const list = $('open-list');
    list.innerHTML = '';
    for (const it of openItems) {
      const div = document.createElement('div');
      div.className = 'oq';
      const jobs = it.jobs || [];
      const jobLinks = jobs.slice(0, 3).map((j) => `<a href="${esc(j.url)}" target="_blank" rel="noopener">${
        esc(j.title || 'a job')}${j.company ? ` at ${esc(j.company)}` : ''}</a>`).join(', ');
      const meta = [jobs.length ? `${plural(jobs.length, 'job')} waited for this: ${jobLinks}${jobs.length > 3 ? ` and ${jobs.length - 3} more` : ''}` : '',
        it.hint ? `LinkedIn said: ${esc(it.hint)}` : ''].filter(Boolean).join('. ');
      div.innerHTML = `<div class="oq-q">${esc(it.question)}</div><div class="oq-meta">${meta}</div>
        <div class="oq-input"></div><div class="q-chips"></div>
        <div class="row end"><button class="btn ghost-danger" type="button" data-dismiss>Dismiss</button>
        <button class="btn primary" type="button" data-save>Save answer</button></div>`;
      list.appendChild(div);
      let w = null;
      const submit = () => saveOpen(it, w);
      w = makeAnswer(div.querySelector('.oq-input'), it, submit);
      renderChips(div.querySelector('.q-chips'), { ...it, loading: false }, w);
      div.querySelector('[data-save]').addEventListener('click', submit);
      div.querySelector('[data-dismiss]').addEventListener('click', () => dismissOpen(it));
    }
  }
  async function saveOpen(it, w) {
    const answer = w.value();
    if (!answer || (Array.isArray(answer) && !answer.length)) { toast('Type an answer or tap a suggestion first.', true); return; }
    try {
      const r = await api('/api/open-questions', { key: it.key, answer });
      toast(r.retried ? `Saved. ${plural(r.retried, 'job')} will be tried again.` : 'Answer saved.');
      openItems = openItems.filter((x) => x.key !== it.key);
      renderOpen();
      loadFacts();
    } catch (e) { toast(e.message, true); }
    refresh();
  }
  async function dismissOpen(it) {
    try {
      await api('/api/open-questions', { key: it.key, dismiss: true });
      openItems = openItems.filter((x) => x.key !== it.key);
      renderOpen();
    } catch (e) { toast(e.message, true); }
    refresh();
  }

  // ------------------------------------------------------------------ your info
  const CAT_HINTS = {
    Personal: 'Nationality, birth date, marital status…',
    Contact: 'Email, address, other numbers…',
    Education: 'Degree, university, major, GPA…',
    Experience: 'Years with a skill or tool. When a form asks how long you worked with it, this comes before your CV.',
    Skills: 'Things you know or use, like “Do you know Linux? Yes”.',
    Certifications: 'CCNA, AWS… and when you got them.',
    Languages: 'Languages you speak and how well.',
    'Salary & availability': 'Pay and start dates your main answers do not cover.',
    'Work preferences': 'Remote work, shifts, travel, overtime…',
    'Work permit': 'Visas, permits, citizenship.',
    Links: 'LinkedIn, GitHub, portfolio.',
    Other: 'Anything else forms ask.',
  };
  let facts = [];
  let categories = [];
  const openCats = new Set();  // categories you opened stay open
  async function loadInfo() {
    loadOpen();
    await loadProfile();
    renderProfileForm($('s-answers'), false);
    paintMainCount();
    await loadFacts();
  }
  function paintMainCount() {
    const saved = (profileData && profileData.profile) || {};
    $('main-n').textContent = PF.filter((f) => saved[f.k]).length || '';
  }
  async function loadFacts() {
    try {
      const d = await api('/api/facts');
      facts = d.facts || [];
      categories = d.categories || [];
      renderCats();
    } catch (e) { toast(e.message, true); }
  }
  const normQ = (q) => String(q).trim().toLowerCase().replace(/\s+/g, ' ');
  const valueText = (v) => (Array.isArray(v) ? v.join(' | ') : String(v == null ? '' : v));
  function factRow(f) {
    const asked = (f.asked || []).filter((a) => a !== normQ(f.label));
    const exp = f.category === 'Experience' && /^\d+(\.\d+)?$/.test(valueText(f.value).trim());
    return `<div class="fact" data-id="${esc(f.id)}" data-list="${Array.isArray(f.value) ? 1 : ''}">
      <div class="fact-label">${esc(f.label)}${f.source === 'ai' ? ' <span class="tag info">AI guess</span>' : ''}</div>
      <div class="row"><input type="text" class="fact-value" value="${esc(valueText(f.value))}" aria-label="Answer to ${esc(f.label)}">${
        exp ? '<span class="unit">years</span>' : ''}<button class="icon-btn" data-del type="button" aria-label="Delete ${esc(f.label)}">×</button></div>
      <details class="fact-more"><summary>More</summary>
        ${asked.length ? `<p class="hint">Also answered:</p><ul class="asked">${asked.map((a) => `<li>${esc(a)}</li>`).join('')}</ul>` : ''}
        <label class="move">Category <select data-move>${categories.map((c) => `<option${c === f.category ? ' selected' : ''}>${esc(c)}</option>`).join('')}</select></label>
      </details></div>`;
  }
  function renderCats() {
    const q = $('info-q').value.trim().toLowerCase();
    const box = $('cats');
    const hit = (f) => !q || `${f.label} ${valueText(f.value)} ${(f.asked || []).join(' ')} ${f.category}`.toLowerCase().includes(q);
    let shown = 0;
    box.innerHTML = categories.map((c) => {
      const list = facts.filter((f) => f.category === c && hit(f));
      if (q && !list.length) return '';
      shown += list.length;
      const exp = c === 'Experience';
      return `<details class="panel cat" data-cat="${esc(c)}"${openCats.has(c) || (q && list.length) ? ' open' : ''}>
        <summary><span>${esc(c)}</span><b class="cat-n">${list.length || ''}</b></summary>
        <div class="cat-body"><p class="hint">${esc(CAT_HINTS[c] || '')}</p>
        ${list.map(factRow).join('')}
        <div class="add-fact">
          <input type="text" class="af-label" placeholder="${exp ? 'Skill or tool, like Python' : 'Question, like Do you have a car?'}" aria-label="New ${esc(c)} question">
          <div class="row"><input type="text" class="af-value" placeholder="${exp ? 'Years, like 3' : 'Answer'}"${exp ? ' inputmode="decimal"' : ''} aria-label="Answer">
          <button class="btn" data-add type="button">Add</button></div>
        </div></div></details>`;
    }).join('');
    $('info-empty').hidden = !q || shown > 0;
    box.querySelectorAll('.fact').forEach((row) => {
      const id = row.dataset.id;
      const input = row.querySelector('.fact-value');
      input.addEventListener('change', () => {
        const v = input.value.trim();
        if (!v) { toast('An answer cannot be empty. Use × to delete it.', true); return; }
        changeFact({ op: 'update', id, value: row.dataset.list ? v.split(/\s*\|\s*/).filter(Boolean) : v }, 'Saved.');
      });
      row.querySelector('[data-del]').addEventListener('click', () => changeFact({ op: 'delete', id }, 'Deleted.'));
      row.querySelector('[data-move]').addEventListener('change', (e) => {
        openCats.add(e.target.value);  // show where it went
        changeFact({ op: 'update', id, category: e.target.value }, `Moved to ${e.target.value}.`);
      });
    });
    box.querySelectorAll('details.cat').forEach((cat) => {
      cat.addEventListener('toggle', () => {
        if (q) return;  // opened by the search
        if (cat.open) openCats.add(cat.dataset.cat); else openCats.delete(cat.dataset.cat);
      });
      const add = () => {
        const label = cat.querySelector('.af-label').value.trim();
        const value = cat.querySelector('.af-value').value.trim();
        if (!label || !value) { toast(cat.dataset.cat === 'Experience' ? 'Type the skill and the years.' : 'Type the question and the answer.', true); return; }
        changeFact({ op: 'add', category: cat.dataset.cat, label, value }, 'Added.');
      };
      cat.querySelector('[data-add]').addEventListener('click', add);
      cat.querySelector('.af-value').addEventListener('keydown', (e) => { if (e.key === 'Enter') add(); });
    });
  }
  async function changeFact(body, done) {
    try {
      const d = await api('/api/facts', body);
      facts = d.facts || facts;
      categories = d.categories || categories;
      renderCats();
      toast(done);
      refresh();
    } catch (e) { toast(e.message, true); }
  }
  $('info-q').addEventListener('input', renderCats);

  // ------------------------------------------------------------------ jobs
  let jobs = [];
  async function loadJobs() {
    try {
      jobs = (await api('/api/jobs')).jobs;
      renderJobs();
    } catch (e) { toast(e.message, true); }
  }
  function renderJobs() {
    const f = $('jobs-filter').value;
    const q = $('jobs-q').value.trim().toLowerCase();
    store('aa-jobs-filter', f);
    const rows = jobs.filter((j) => (f === 'all' || j.group === f) && (!q || `${j.title} ${j.company}`.toLowerCase().includes(q)));
    $('jobs-count').textContent = plural(rows.length, 'job');
    const failed = jobs.filter((j) => ['error', 'stuck'].includes(j.status)).length;
    $('jobs-retry').hidden = !failed || !state || state.running;
    $('jobs-retry-title').textContent = `${plural(failed, 'job')} had problems`;
    $('jobs-empty').hidden = rows.length > 0;
    $('jobs-rows').innerHTML = rows.slice(0, 600).map((j) => {
      const qa = (j.qa || []).length ? `<details><summary>${plural(j.qa.length, 'answer')} given</summary><ul>${
        j.qa.map((x) => `<li><span class="muted">${esc(x.q)}:</span> ${esc(x.a)}</li>`).join('')}</ul></details>` : '';
      const reason = j.status === 'stuck' ? whyText(j.reason) : esc(String(j.reason || '').split(' | ').pop());
      return `<li><a class="job" href="${esc(j.url)}" target="_blank" rel="noopener">${esc(j.title || `Job ${j.id}`)}</a>
        <span class="co">${esc(j.company || '')}</span>
        <div class="meta"><span class="tag ${esc(j.tone)}">${esc(j.label)}</span>${j.score == null ? '' : `<span>fit ${j.score}/10</span>`}<span>${esc(fmtWhen(j.ts))}</span></div>
        ${reason ? `<span class="why">${reason}</span>` : ''}${qa}</li>`;
    }).join('');
  }
  $('btn-jobs-retry').addEventListener('click', async () => {
    await startBot('apply', { retry: true });
    go('home');
  });
  $('jobs-filter').value = store('aa-jobs-filter') || 'applied';
  $('jobs-filter').addEventListener('change', renderJobs);
  $('jobs-q').addEventListener('input', renderJobs);
  setInterval(() => { if (view === 'jobs' && !document.hidden) loadJobs(); }, 15000);

  // ------------------------------------------------------------------ jobs you apply to (with the app's help)
  const ICON = {
    search: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 9.5 16a6.5 6.5 0 0 0 4.2-1.6l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"/></svg>',
    open: '<svg class="go" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-2v7zM14 3v2h3.6l-9.8 9.8 1.4 1.4L19 6.4V10h2V3h-7z"/></svg>',
    globe: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.9 6h-3a15.7 15.7 0 0 0-1.3-3.6A8 8 0 0 1 18.9 8zM12 4c.8 1.2 1.5 2.6 1.9 4h-3.8c.4-1.4 1.1-2.8 1.9-4zM4.3 14a8.2 8.2 0 0 1 0-4h3.4a16.5 16.5 0 0 0 0 4H4.3zm.8 2h3a15.7 15.7 0 0 0 1.3 3.6A8 8 0 0 1 5.1 16zm3-8h-3a8 8 0 0 1 4.3-3.6C8.8 5.5 8.4 6.7 8 8zm3.9 12c-.8-1.2-1.5-2.6-1.9-4h3.8c-.4 1.4-1.1 2.8-1.9 4zm2.3-6H9.7a14.7 14.7 0 0 1 0-4h4.6a14.7 14.7 0 0 1 0 4zm.3 5.6c.6-1.1 1-2.3 1.3-3.6h3a8 8 0 0 1-4.3 3.6zm1.8-5.6a16.5 16.5 0 0 0 0-4h3.4a8.2 8.2 0 0 1 0 4h-3.4z"/></svg>',
  };
  const STAGES = ['applied', 'interview', 'offer', 'rejected'];
  const STAGE_NAMES = { applied: 'Applied', interview: 'Interview', offer: 'Offer', rejected: 'Rejected' };
  let queue = [];
  let searchLinks = [];
  let searchWhere = '';
  const hostOf = (u) => { const m = /^https?:\/\/(?:www\.)?([^/:?#]+)/i.exec(String(u || '')); return m ? m[1] : ''; };
  const ago = (ts) => {
    const sec = Date.now() / 1000 - Number(ts || 0);
    if (!ts || sec < 0) return '';
    if (sec < 90) return 'just now';
    if (sec < 3600) return `${Math.round(sec / 60)} min ago`;
    if (sec < 86400) return `${Math.round(sec / 3600)} h ago`;
    if (sec < 2 * 86400) return 'yesterday';
    return new Date(ts * 1000).toLocaleDateString([], { month: 'short', day: 'numeric' });
  };
  const fmtDay = (ts) => (ts ? new Date(ts * 1000).toLocaleDateString([], { month: 'short', day: 'numeric' }) : '');
  const waiting = () => queue.filter((x) => x.state === 'new');
  // Best fits first, then the newest.
  const nextJobs = () => waiting().slice().sort((a, b) => ((typeof b.fit === 'number' ? b.fit : 5) - (typeof a.fit === 'number' ? a.fit : 5))
    || (b.added - a.added));
  const findJob = (id) => queue.find((x) => x.id === id);
  function chanTag(j) {
    if (j.channel === 'linkedin') return '<span class="chan linkedin">LinkedIn</span>';
    if (j.channel === 'auto') return '<span class="chan auto">Auto apply</span>';
    return '<span class="chan">Company site</span>';
  }
  function tagsHtml(j) {
    let fitBadge = '';
    if (typeof j.score === 'number' && j.score > 0) {
      const cls = j.score >= 75 ? 'high' : j.score >= 60 ? 'mid' : '';
      fitBadge = `<span class="fit ${cls}">${j.score}% Match</span>`;
    } else if (typeof j.fit === 'number') {
      const cls = j.fit >= 7 ? 'high' : j.fit >= 5 ? 'mid' : '';
      fitBadge = `<span class="fit ${cls}">${j.fit * 10}% Match</span>`;
    }
    return chanTag(j) + fitBadge;
  }

  let prevQueueCount = -1;
  async function loadQueue(quiet) {
    try {
      const d = await api('/api/copilot');
      const prev = prevQueueCount;
      queue = d.items || [];
      prevQueueCount = queue.length;
      searchLinks = d.search_links || [];
      searchWhere = d.location || '';
      paintQueue();
      if (prev >= 0 && queue.length > prev) {
        const newest = queue[0];
        if (newest) {
          showPopupPill({
            type: 'job',
            icon: '💼',
            title: 'New Matching Job',
            sub: (newest.title || 'Job') + (newest.company ? ' • ' + newest.company : ''),
            actText: 'View',
            onAct: () => { go('queue'); openSheet(newest.id); }
          });
        }
      }
    } catch (e) { if (!quiet) toast(e.message, true); }
  }
  function paintQueue() {
    renderDesk();
    renderFind();
    renderQueue();
    if (!$('sheet').hidden) paintSheet(false);
  }
  function todayApplied() {
    if (!state) return 0;
    return ((state.copilot || {}).today || 0) + (auto() ? (state.stats || {}).today || 0 : 0);
  }
  function renderDesk() {
    if (!state) return;
    const list = nextJobs();
    const n = list.length;
    const goal = (state.stats && state.stats.goal) || 10;
    const today = todayApplied();
    $('goal-today').textContent = today;
    $('goal-of').textContent = goal;
    $('goal-meter').style.width = `${Math.min(100, Math.round((today / goal) * 100))}%`;
    const job = list[0];
    $('next-job').hidden = !job;
    $('next-empty').hidden = !!job;
    $('next-actions').hidden = !job;
    $('next-label').textContent = !job ? 'Next up' : n === 1 ? '1 job waiting' : `${n} jobs waiting`;
    if (job) {
      $('next-title').textContent = job.title;
      $('next-co').textContent = job.company || hostOf(job.url);
      $('next-tags').innerHTML = tagsHtml(job);
      $('next-apply').dataset.id = job.id;
      $('next-more').hidden = n < 2;
      $('next-more-n').textContent = 'See all';
    }
    const tab = $('tab-queue-n');
    tab.hidden = !n;
    tab.textContent = n > 99 ? '99+' : n;
  }
  $('next-apply').addEventListener('click', () => openSheet($('next-apply').dataset.id));
  $('next-more').addEventListener('click', () => go('queue'));

  // Searches: LinkedIn (in the LinkedIn app) for each of your job titles, and a few sites in the app's browser.
  function siteLinks() {
    const titles = searchLinks.map((l) => l.title);
    if (!titles.length) return [];
    const where = searchWhere || '';
    const q = titles.slice(0, 3).map((t) => (titles.length > 1 ? `"${t}"` : t)).join(' OR ');
    const out = [{ name: 'Google Jobs', url: `https://www.google.com/search?q=${encodeURIComponent(`${q} jobs ${where}`.trim())}&ibp=htl;jobs` }];
    if (!where || /egypt|cairo|giza|alex|مصر/i.test(where)) {
      out.push({ name: 'Wuzzuf', url: `https://wuzzuf.net/search/jobs/?q=${encodeURIComponent(titles[0])}` });
    }
    return out;
  }
  function renderFind() {
    const box = $('find-links');
    const sig = JSON.stringify([searchLinks, searchWhere]);
    if (box.dataset.sig === sig) return;
    box.dataset.sig = sig;
    box.innerHTML = searchLinks.map((l, i) => `<button type="button" class="find-link" data-i="${i}">${ICON.search}<span><b>${
      esc(l.title)}</b><small>LinkedIn${searchWhere ? `, ${esc(searchWhere)}` : ''}: Easy Apply, newest first</small></span>${ICON.open}</button>`).join('');
    box.querySelectorAll('.find-link').forEach((b) => b.addEventListener('click', () => {
      const l = searchLinks[+b.dataset.i];
      api('/api/open-link', { url: l.url, how: 'linkedin' })
        .then(() => toast('On a job you like: Share, then JSTB.'))
        .catch((e) => toast(e.message, true));
    }));
    $('find-none').hidden = searchLinks.length > 0;
    const sites = siteLinks();
    $('find-sites').innerHTML = sites.map((x, i) => `<button type="button" class="site-chip" data-i="${i}">${ICON.globe}${esc(x.name)}</button>`).join('');
    $('find-sites').querySelectorAll('.site-chip').forEach((b) => b.addEventListener('click', () => {
      api('/api/open-link', { url: sites[+b.dataset.i].url, how: 'inapp' }).catch((e) => toast(e.message, true));
    }));
  }

  async function addJob(input) {
    const text = input.value.trim();
    if (!text) { toast('Paste a link to a job first.', true); input.focus(); return; }
    try {
      const r = await api('/api/copilot/add', { text, subject: '' });
      input.value = '';
      input.blur();
      queue = r.items || queue;
      paintQueue();
      toast(r.item.again ? 'Already in your jobs.' : 'Added to your jobs.');
      openSheet(r.item.id);
      refresh();
    } catch (e) { toast(e.message, true); }
  }
  [['home-link', 'home-link-btn'], ['queue-link', 'queue-link-btn']].forEach(([i, b]) => {
    $(b).addEventListener('click', () => addJob($(i)));
    $(i).addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addJob($(i)); } });
  });

  let qFilter = store('aa-queue-filter') === 'skipped' ? 'skipped' : 'new';
  function renderQueue() {
    const skipped = queue.filter((x) => x.state === 'skipped');
    $('qf-new').textContent = waiting().length || '';
    $('qf-skipped').textContent = skipped.length || '';
    document.querySelectorAll('[data-qf]').forEach((b) => {
      b.classList.toggle('on', b.dataset.qf === qFilter);
      b.setAttribute('aria-selected', b.dataset.qf === qFilter ? 'true' : 'false');
    });
    const list = qFilter === 'new' ? nextJobs() : skipped;
    $('queue-list').innerHTML = list.map((j) => `<li class="jcard"><button type="button" data-id="${esc(j.id)}"><span class="jc-title">${
      esc(j.title)}</span><span class="jc-co">${esc(j.company || hostOf(j.url))}</span><span class="tags">${tagsHtml(j)}<span class="when">${
      esc(ago(j.added))}</span></span></button></li>`).join('');
    $('queue-list').querySelectorAll('button[data-id]').forEach((b) => b.addEventListener('click', () => openSheet(b.dataset.id)));
    $('queue-empty').hidden = list.length > 0;
    $('queue-empty-title').textContent = qFilter === 'new' ? 'No jobs waiting' : 'Nothing skipped';
    $('queue-empty-text').textContent = qFilter === 'new'
      ? 'Find jobs from the Home screen. On a job you like, tap Share and pick JSTB.'
      : 'Jobs you skip wait here, in case you change your mind.';
  }
  document.querySelectorAll('[data-qf]').forEach((b) => b.addEventListener('click', () => {
    qFilter = b.dataset.qf;
    store('aa-queue-filter', qFilter);
    renderQueue();
  }));

  // ------------------------------------------------------------------ the job sheet
  let sheetId = null;
  const sheetOpen = () => !$('sheet').hidden || !$('ask').hidden;
  function closeSheets() {
    $('sheet').hidden = true;
    $('ask').hidden = true;
    sheetId = null;
    document.body.style.overflow = '';
  }
  document.querySelectorAll('.sheet-wrap [data-close]').forEach((x) => x.addEventListener('click', closeSheets));
  function openSheet(id) {
    if (!findJob(id)) return;
    $('ask').hidden = true;
    sheetId = id;
    $('sheet').hidden = false;
    document.body.style.overflow = 'hidden';
    $('sheet').querySelector('.sheet-body').scrollTop = 0;
    paintSheet(true);
  }
  function paintSheet(first) {
    const j = findJob(sheetId);
    if (!j) { closeSheets(); return; }
    $('sh-title').textContent = j.title;
    $('sh-co').textContent = j.company || hostOf(j.url);
    $('sh-tags').innerHTML = tagsHtml(j) + (j.state === 'applied' ? `<span class="stage-label s-${esc(j.stage || 'applied')}">${
      esc(STAGE_NAMES[j.stage] || 'Applied')} ${esc(fmtDay(j.applied_at))}</span>` : '') + (j.state === 'skipped' ? '<span class="chan">Skipped</span>' : '');
    $('sh-why').hidden = !j.why;
    $('sh-why').textContent = j.why || '';
    const li = j.channel === 'linkedin';
    $('sh-open').textContent = 'Fill & Review (1-Tap Apply)';
    $('sh-open-hint').textContent = 'Opens inside JSTB, fills the form with your CV data, and guides you to Submit with 1 tap.';
    const extBtn = $('sh-open-ext');
    if (extBtn) {
      extBtn.hidden = !li;
      extBtn.textContent = 'Or open in official LinkedIn app';
    }
    if (first) {
      renderAnswers();
      $('sh-q').value = '';
      $('sh-q-out').hidden = true;
      $('sh-note').value = j.note || '';
    }
    const letter = j.letter || '';
    $('sh-letter').hidden = !letter;
    $('sh-letter').textContent = letter;
    $('sh-letter-copy').hidden = !letter;
    if (!$('sh-letter-btn').dataset.busy) $('sh-letter-btn').textContent = letter ? 'Write another' : 'Write one for this job';
    $('sh-letter-hint').textContent = '';
    const applied = j.state === 'applied';
    $('sh-applied').textContent = applied ? 'Applied' : 'I applied';
    $('sh-applied').disabled = applied;
    $('sh-skip').textContent = j.state === 'skipped' ? 'Back to To apply' : 'Skip';
    $('sh-skip').hidden = applied;
    $('sheet').querySelector('.sheet-foot').style.gridTemplateColumns = applied ? '1fr' : '';
  }

  // Your answers, to copy into a form in another app (LinkedIn): one tap copies one answer.
  const NOTICE_TEXT = (d) => (String(d) === '0' ? 'Immediately' : `${d} days`);
  async function renderAnswers() {
    if (!profileData) await loadProfile();
    if (!facts.length) {
      try { const d = await api('/api/facts'); facts = d.facts || []; categories = d.categories || categories; } catch (e) { /* none */ }
    }
    const p = (profileData && profileData.profile) || {};
    const a = acc();
    const cur = p.currency || 'EGP';
    const years = yearsSince(p.graduation_year);
    const list = [
      ['Name', a.name], ['Email', a.email], ['Mobile', p.phone], ['City', p.city],
      ['Expected salary', p.expected_salary ? `${p.expected_salary} ${cur}` : '', p.expected_salary],
      ['Current salary', p.current_salary ? `${p.current_salary} ${cur}` : '', p.current_salary],
      ['Notice period', p.notice_days !== undefined && p.notice_days !== '' ? NOTICE_TEXT(p.notice_days) : '', p.notice_days],
      ['Years of experience', years >= 0 ? String(years) : ''],
      ['Graduated', p.graduation_year], ['English', p.english], ['Military service', p.military],
    ].filter((x) => x[1]);
    for (const f of facts) {
      if (f.category === 'Links' && f.value) list.push([f.label, valueText(f.value), valueText(f.value), true]);
    }
    const box = $('sh-answers');
    box.innerHTML = list.map((x, i) => `<button type="button" class="ans-chip${x[3] || x[0] === 'Email' || String(x[1]).length > 18 ? ' wide' : ''}" data-i="${i}"><small>${
      esc(x[0])}</small><b>${esc(x[1])}</b></button>`).join('') || '<p class="hint">Your answers show up here once you save them in Your info.</p>';
    box.querySelectorAll('.ans-chip').forEach((b) => b.addEventListener('click', () => {
      const x = list[+b.dataset.i];
      copyText(x[2] != null && x[2] !== '' ? x[2] : x[1]);
      box.querySelectorAll('.copied').forEach((c) => c.classList.remove('copied'));
      b.classList.add('copied');
      toast(`Copied: ${x[0]}`);
    }));
  }
  function copyText(t) {
    try {
      if (window.AA && window.AA.copy) { window.AA.copy(String(t)); return; }
    } catch (e) { /* fall back */ }
    try { navigator.clipboard.writeText(String(t)); } catch (e) { /* no clipboard */ }
  }

  // A question from the form: answered from your info, else by the AI from your CV.
  async function askAnswer() {
    const q = $('sh-q').value.trim();
    const out = $('sh-q-out');
    if (!q) { toast('Type the question the form asks.', true); $('sh-q').focus(); return; }
    const long = /^(why|describe|tell|explain|what makes|how would|please describe|write)/i.test(q) || q.length > 90;
    out.hidden = false;
    out.innerHTML = '<span class="loading-line">Finding the answer…</span>';
    $('sh-q-btn').disabled = true;
    try {
      const r = await api('/api/fill', { id: sheetId, fields: [{ key: 'q', label: q, kind: long ? 'textarea' : 'text', options: [], value: '',
        placeholder: '', maxlength: 0, required: true }] });
      const v = r.answers && r.answers.q;
      if (v == null || v === '') {
        out.innerHTML = `<p class="hint">${esc(r.ai_problem || 'Your answers do not cover this one. Add it in Your info, then it is ready for every form.')}</p>`;
      } else {
        const t = valueText(v);
        out.innerHTML = `<button type="button" class="ans-chip wide"><small>Tap to copy</small><b>${esc(t)}</b></button>`;
        out.querySelector('.ans-chip').addEventListener('click', (e) => {
          copyText(t);
          e.currentTarget.classList.add('copied');
          toast('Copied the answer.');
        });
        facts = [];  // a new answer may have joined your info
      }
    } catch (e) {
      out.innerHTML = `<p class="hint bad-text">${esc(e.message)}</p>`;
    }
    $('sh-q-btn').disabled = false;
  }
  $('sh-q-btn').addEventListener('click', askAnswer);
  $('sh-q').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); askAnswer(); } });

  $('sh-letter-btn').addEventListener('click', async () => {
    const btn = $('sh-letter-btn');
    const id = sheetId;
    btn.dataset.busy = '1';
    btn.disabled = true;
    btn.textContent = 'Writing…';
    try {
      const r = await api('/api/copilot/letter', { id });
      const it = findJob(id);
      if (it) it.letter = r.letter;
      toast('Cover letter ready. Tap Copy.');
    } catch (e) { toast(e.message, true); }
    delete btn.dataset.busy;
    btn.disabled = false;
    if (sheetId === id) paintSheet(false);
  });
  $('sh-letter-copy').addEventListener('click', () => {
    const j = findJob(sheetId);
    if (j && j.letter) { copyText(j.letter); toast('Copied the cover letter.'); }
  });
  $('sh-note').addEventListener('change', () => {
    const id = sheetId;
    api('/api/copilot/update', { id, note: $('sh-note').value }).then((r) => { queue = r.items || queue; toast('Note saved.'); })
      .catch((e) => toast(e.message, true));
  });

  let awaiting = null;  // a job you went to apply to in the LinkedIn app
  $('sh-open').addEventListener('click', async () => {
    const j = findJob(sheetId);
    if (!j) return;
    try {
      await api('/api/copilot/open', { id: j.id, fill: true, external: false });
    } catch (e) { toast(e.message, true); }
  });
  if ($('sh-open-ext')) {
    $('sh-open-ext').addEventListener('click', async () => {
      const j = findJob(sheetId);
      if (!j) return;
      try {
        await api('/api/copilot/open', { id: j.id, external: true });
        awaiting = { id: j.id, at: Date.now() };
      } catch (e) { toast(e.message, true); }
    });
  }
  $('sh-skip').addEventListener('click', async () => {
    const j = findJob(sheetId);
    if (!j) return;
    const back = j.state === 'skipped';
    try {
      const r = await api('/api/copilot/update', { id: j.id, state: back ? 'new' : 'skipped' });
      queue = r.items || queue;
      closeSheets();
      paintQueue();
      toast(back ? 'Back in To apply.' : 'Skipped. It waits under Skipped.');
      refresh();
    } catch (e) { toast(e.message, true); }
  });
  $('sh-remove').addEventListener('click', async () => {
    const id = sheetId;
    try {
      const r = await api('/api/copilot/remove', { id });
      queue = r.items || queue;
      closeSheets();
      paintQueue();
      toast('Removed from your jobs.');
      refresh();
    } catch (e) { toast(e.message, true); }
  });
  $('sh-applied').addEventListener('click', () => markApplied(sheetId));

  async function markApplied(id) {
    try {
      const r = await api('/api/copilot/update', { id, state: 'applied' });
      queue = r.items || queue;
      paintQueue();
      await refresh();
      showSent(id);
      loadTracker(true);
    } catch (e) { toast(e.message, true); }
  }

  // ------------------------------------------------------------------ did you apply? / sent
  let askId = null;
  function showAsk(id) {
    const j = findJob(id);
    if (!j || j.state !== 'new') return;
    closeSheets();
    askId = id;
    $('ask-q').hidden = false;
    $('ask-sent').hidden = true;
    $('ask-job').textContent = `${j.title}${j.company ? ` at ${j.company}` : ''}`;
    $('ask').hidden = false;
    document.body.style.overflow = 'hidden';
  }
  function showSent(id) {
    closeSheets();
    askId = id;
    const next = nextJobs().find((x) => x.id !== id);
    const goal = (state && state.stats && state.stats.goal) || 10;
    const today = todayApplied();
    $('ask-q').hidden = true;
    $('ask-sent').hidden = false;
    const stamp = $('ask-sent').querySelector('.stamp');
    stamp.replaceWith(stamp.cloneNode(true));  // plays the stamp again
    $('ask-sent-title').textContent = today >= goal ? `${today} today: you reached your goal` : `${today} of ${goal} today`;
    $('ask-next-text').textContent = next ? `Next: ${next.title}${next.company ? ` at ${next.company}` : ''}`
      : 'Your list is empty. Find more jobs from the Home screen.';
    $('ask-next').hidden = !next;
    $('ask-next').dataset.id = next ? next.id : '';
    $('ask').hidden = false;
    document.body.style.overflow = 'hidden';
  }
  $('ask-yes').addEventListener('click', () => markApplied(askId));
  $('ask-later').addEventListener('click', () => { const id = askId; closeSheets(); openSheet(id); });
  $('ask-skip').addEventListener('click', async () => {
    try {
      const r = await api('/api/copilot/update', { id: askId, state: 'skipped' });
      queue = r.items || queue;
      paintQueue();
      toast('Skipped. It waits under Skipped.');
    } catch (e) { toast(e.message, true); }
    closeSheets();
  });
  $('ask-next').addEventListener('click', () => { const id = $('ask-next').dataset.id; closeSheets(); openSheet(id); });
  $('ask-done').addEventListener('click', closeSheets);

  // Back from the LinkedIn app, or from a company's site in the app's browser.
  let googleLinking = false;   // back from adding Google to this account: show it
  window.aaResumed = () => {
    driveResumed();
    if (googleLinking) {
      googleLinking = false;
      setTimeout(() => api('/api/account/refresh', {}).then((r) => {
        if (r.account && r.account.google) toast('Google is added: you can sign in with it from now on.');
        refresh();
      }).catch(() => {}), 1500);
    }
    googleResumed();
    if (awaiting && Date.now() - awaiting.at > 3000) {
      const id = awaiting.id;
      awaiting = null;
      loadQueue(true).then(() => showAsk(id));
    }
    refresh();
  };
  window.aaBrowserClosed = async (id, applied) => {
    await loadQueue(true);
    await refresh();
    if (!id) return;
    if (applied) { showSent(id); loadTracker(true); } else showAsk(id);
  };
  window.aaRefreshJobs = () => loadQueue(true);

  // A job shared to the app (from LinkedIn or a browser).
  async function takePending() {
    let raw = '';
    try { raw = window.AA && window.AA.pending ? window.AA.pending() : ''; } catch (e) { return; }
    if (!raw) return;
    let d = {};
    try { d = JSON.parse(raw); } catch (e) { return; }
    if (d.ok === false) { toast(d.error || 'That share had no job link.', true); return; }
    if (!d.item) return;
    if (!state || !state.setup.setup_done || mustUpdate()) { toast('Saved to your jobs. Finish the setup to apply.'); return; }
    await loadQueue(true);
    if (view !== 'queue') go('queue');
    openSheet(d.item.id);
    toast(d.item.again ? 'Already in your jobs.' : 'Added to your jobs.');
  }
  window.aaPending = () => { takePending(); };

  // ------------------------------------------------------------------ applied: every application and its stage
  let tracker = { items: [], stages: {}, total: 0 };
  let trStage = '';
  let trOpen = null;
  async function loadTracker(quiet) {
    try {
      tracker = await api('/api/tracker');
      renderTracker();
    } catch (e) { if (!quiet) toast(e.message, true); }
  }
  function renderTracker() {
    const st = tracker.stages || {};
    $('tr-n-all').textContent = tracker.total || 0;
    STAGES.forEach((k) => { $(`tr-n-${k}`).textContent = st[k] || 0; });
    document.querySelectorAll('#tr-stages [data-st]').forEach((b) => {
      b.classList.toggle('on', b.dataset.st === trStage);
      b.setAttribute('aria-selected', b.dataset.st === trStage ? 'true' : 'false');
    });
    $('c-week').textContent = tracker.week || 0;
    $('c-interviews').textContent = (st.interview || 0) + (st.offer || 0);
    $('c-total').textContent = tracker.total || 0;
    const q = $('tr-q').value.trim().toLowerCase();
    const all = tracker.items || [];
    const rows = all.filter((x) => (!trStage || x.stage === trStage) && (!q || `${x.title} ${x.company}`.toLowerCase().includes(q)));
    $('tr-empty').hidden = all.length > 0;
    $('tr-count').textContent = all.length ? (rows.length ? plural(rows.length, 'application') : 'Nothing here.') : '';
    $('tr-list').innerHTML = rows.slice(0, 500).map((x) => {
      const open = trOpen === x.id;
      return `<li class="jcard" data-id="${esc(x.id)}"><button type="button" class="tr-main" aria-expanded="${open}"><span class="jc-title">${
        esc(x.title || 'A job')}</span><span class="jc-co">${esc(x.company || hostOf(x.url))}</span><span class="tags"><span class="stage-label s-${
        esc(x.stage)}">${esc(STAGE_NAMES[x.stage] || x.stage)}</span>${chanTag(x)}<span class="when">${esc(fmtDay(x.at))}</span></span>`
        + `<span class="steps s-${esc(x.stage)}" aria-hidden="true"><i></i><i></i><i></i></span></button>${open
        ? `<div class="stage-pick" role="group" aria-label="Stage">${STAGES.map((k) => `<button type="button" data-stage="${k}" class="${
          k === x.stage ? 'on' : ''}">${STAGE_NAMES[k]}</button>`).join('')}</div><div class="stage-more">${
          x.url ? '<button type="button" class="linklike" data-openjob>Open the job</button>' : '<span></span>'}</div>` : ''}</li>`;
    }).join('');
    $('tr-list').querySelectorAll('.jcard').forEach((li) => {
      const id = li.dataset.id;
      const x = all.find((y) => y.id === id);
      li.querySelector('.tr-main').addEventListener('click', () => { trOpen = trOpen === id ? null : id; renderTracker(); });
      li.querySelectorAll('[data-stage]').forEach((b) => b.addEventListener('click', () => setStage(id, b.dataset.stage)));
      const o = li.querySelector('[data-openjob]');
      if (o && x) o.addEventListener('click', () => api('/api/open-link', { url: x.url, how: /linkedin\.com/.test(x.url) ? 'linkedin' : 'browser' })
        .catch((e) => toast(e.message, true)));
    });
  }
  async function setStage(id, stage) {
    try {
      tracker = await api('/api/tracker/stage', { id, stage });
      renderTracker();
      toast(stage === 'interview' ? 'Interview: good luck!' : stage === 'offer' ? 'An offer, well done.' : `Marked as ${STAGE_NAMES[stage]}.`);
      loadQueue(true);
    } catch (e) { toast(e.message, true); }
  }
  document.querySelectorAll('#tr-stages [data-st]').forEach((b) => b.addEventListener('click', () => { trStage = b.dataset.st; renderTracker(); }));
  $('tr-q').addEventListener('input', renderTracker);

  // ------------------------------------------------------------------ profile, notices, updates the server asks for
  function renderProfile() {
    if (!state) return;
    const a = acc();
    const name = a.name || String(a.email || '').split('@')[0] || '';
    $('me-initial').textContent = (name.trim()[0] || '?').toUpperCase();
    $('me-name').textContent = a.signed_in ? name : 'Not signed in';
    $('me-email').textContent = a.signed_in ? (a.email || '') : 'Sign in from Settings.';
    $('me-state').textContent = !a.signed_in ? 'Signed out' : a.pro ? 'Pro' : 'Free';
    $('me-state').className = `tag ${!a.signed_in ? '' : a.pro ? 'good' : 'info'}`;
    cvStates();
  }
  function renderAnnounce() {
    const text = String((acc().announcement) || '').trim();
    const hide = !text || store('aa-announce-x') === text;
    $('announce').hidden = hide;
    if (!hide) $('announce-text').textContent = text;
    const n = (state && state.open_questions) || 0;
    $('tab-profile-n').hidden = !n || !auto();
    $('tab-profile-n').textContent = n;
  }
  $('announce-x').addEventListener('click', () => {
    store('aa-announce-x', String(acc().announcement || '').trim());
    $('announce').hidden = true;
  });

  let forceBusy = false;
  function renderForceUpdate() {
    if (!state || view !== 'update') return;
    const u = state.update || {};
    const dl = u.download || {};
    const busyNow = ['downloading', 'installing', 'confirm'].includes(dl.state);
    const btn = $('force-update');
    btn.textContent = isPlay() ? 'Open Google Play' : busyNow || forceBusy ? 'Updating…' : 'Update now';
    btn.disabled = !isPlay() && (busyNow || forceBusy);
    $('force-status').textContent = isPlay() ? 'Google Play has the new version.' : (dl.state !== 'idle' && dl.message) || (u.checking ? 'Looking for the new version…' : '');
    $('force-meter-wrap').hidden = dl.state !== 'downloading';
    $('force-meter').style.width = `${dl.progress || 0}%`;
  }
  $('force-update').addEventListener('click', async () => {
    try {
      if (isPlay()) { await api('/api/update/store', {}); return; }
      forceBusy = true;
      renderForceUpdate();
      if (state.running) await api('/api/stop', {});
      let u = state.update || {};
      if (!(u.last && u.last.available)) {
        await api('/api/update/check', {});
        for (let i = 0; i < 40; i++) {
          await new Promise((r) => setTimeout(r, 500));
          await refresh();
          u = state.update || {};
          if (!u.checking) break;
        }
      }
      if (!(u.last && u.last.available)) {
        toast((u.last && u.last.message) || 'The new version is not out yet. Try again in a few minutes.', true);
      } else {
        await api('/api/update/install', {});
      }
    } catch (e) { toast(e.message, true); }
    forceBusy = false;
    refresh();
  });

  // ------------------------------------------------------------------ start
  window.aaRefresh = () => { refresh(); if (view === 'settings') loadSettings(); };
  window.aaShow = (v) => go(v === 'dashboard' ? 'home' : v);
  setTimeout(() => { if (state && state.setup.setup_done) { loadQueue(true); loadTracker(true); } }, 400);
  setTimeout(() => api('/api/update/check', { auto: true }).catch(() => {}), 1500);
  loop();
  logLoop();
})();
