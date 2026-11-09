/* JSTB: fills a job application form on a company's site that you opened in the app. You check it and submit
   it yourself. collect() lists the form's empty fields; apply(values) fills them and marks them in green. */
(function () {
  'use strict';
  if (window.AAFill) return;
  var SKIP = /^(hidden|submit|button|reset|image|file|password|search)$/i;

  function clean(s) {
    return String(s || '').replace(/\s+/g, ' ').replace(/\s*[*:]+\s*$/, '').replace(/^\s*[*]\s*/, '').trim();
  }
  function visible(el) {
    var st = window.getComputedStyle(el);
    var r = el.getBoundingClientRect();
    return st.visibility !== 'hidden' && st.display !== 'none' && (r.width > 0 || r.height > 0);
  }
  function textOf(el) {
    return el ? clean(el.innerText || el.textContent || '') : '';
  }
  function labelOf(el) {
    if (el.id) {
      var byFor = document.querySelector('label[for="' + (window.CSS && CSS.escape ? CSS.escape(el.id) : el.id) + '"]');
      if (textOf(byFor)) return textOf(byFor);
    }
    var aria = el.getAttribute('aria-label');
    if (clean(aria)) return clean(aria);
    var by = el.getAttribute('aria-labelledby');
    if (by) {
      var t = by.split(/\s+/).map(function (id) { return textOf(document.getElementById(id)); }).join(' ');
      if (clean(t)) return clean(t);
    }
    var wrap = el.closest('label');
    if (wrap && textOf(wrap)) return textOf(wrap);
    var box = el.parentElement;
    for (var i = 0; i < 4 && box; i++, box = box.parentElement) {
      var lab = box.querySelector('label, legend, [class*="label"], [class*="Label"], [class*="question"]');
      if (lab && !lab.contains(el) && textOf(lab)) return textOf(lab);
    }
    return clean(el.getAttribute('placeholder') || el.getAttribute('name') || '');
  }
  function groupLabel(el) {
    var fs = el.closest('fieldset');
    if (fs && textOf(fs.querySelector('legend'))) return textOf(fs.querySelector('legend'));
    var box = el.closest('[role="radiogroup"], [role="group"]');
    if (box) {
      var by = box.getAttribute('aria-labelledby');
      if (by && textOf(document.getElementById(by))) return textOf(document.getElementById(by));
      if (clean(box.getAttribute('aria-label'))) return clean(box.getAttribute('aria-label'));
    }
    var p = el.parentElement;
    for (var i = 0; i < 5 && p; i++, p = p.parentElement) {
      var lab = p.querySelector('legend, [class*="label"], [class*="question"], p, span');
      if (lab && !lab.contains(el) && !lab.querySelector('input') && textOf(lab)) return textOf(lab);
    }
    return clean(el.name || '');
  }
  function optionLabel(input) {
    return labelOf(input) || clean(input.value);
  }
  function isRequired(el, label) {
    return !!(el.required || el.getAttribute('aria-required') === 'true' || /\*\s*$/.test(String(label)));
  }

  // The site's own message under a field: "Enter a decimal number larger than 0.0", "This field is required"...
  var NUM_ERR = /\b(decimal|whole|valid)?\s*number\b|numeric|digits? only|only numbers|أرقام|رقم/i;
  var ERR_SEL = '[role="alert"], [aria-live="assertive"], .artdeco-inline-feedback--error, [class*="error" i], [class*="invalid" i], [class*="danger" i]';
  function errorOf(el) {
    if (!el) return '';
    var ids = (el.getAttribute('aria-errormessage') || '') + ' ' + (el.getAttribute('aria-describedby') || '');
    var found = '';
    ids.split(/\s+/).forEach(function (id) {
      if (found || !id) return;
      var d = document.getElementById(id);
      if (d && visible(d) && (el.getAttribute('aria-invalid') === 'true' || d.matches(ERR_SEL) || d.querySelector(ERR_SEL))) found = clean(textOf(d));
    });
    if (found) return found.slice(0, 200);
    var box = el.closest('.fb-dash-form-element, .jobs-easy-apply-form-element, [data-test-form-element], fieldset, [class*="form-group" i], [class*="form-field" i], [class*="question" i]')
      || (el.parentElement && el.parentElement.parentElement) || el.parentElement;
    if (!box) return '';
    var errs = box.querySelectorAll(ERR_SEL);
    for (var i = 0; i < errs.length; i++) {
      var e = errs[i];
      if (e === el || e.contains(el) || !visible(e)) continue;
      var t = clean(textOf(e));
      if (t.length >= 3 && t.length <= 200 && !/^\*$/.test(t)) return t;
    }
    return el.getAttribute('aria-invalid') === 'true' ? 'The site does not accept this answer.' : '';
  }

  function isNumericInput(el, label) {
    var type = (el.getAttribute('type') || el.tagName).toLowerCase();
    var mode = (el.getAttribute('inputmode') || el.inputMode || '').toLowerCase();
    if (type === 'number' || mode === 'numeric' || mode === 'decimal') return true;
    if (/numeric/i.test(el.id || '') || /numeric/i.test(el.name || '')) return true;   // LinkedIn: ...-numeric
    if (NUM_ERR.test(errorOf(el))) return true;
    var lab = String(label || '').toLowerCase();
    return /\b(how many (years|months|days)|years of experience|how long have you|gross salary|expected salary|current salary|salary|compensation|notice period in days|gpa)\b/i.test(lab)
      || /\b(experience|salary|gross|pay|ctc|wage)\b/i.test(lab);
  }

  function isResumeRadio(el) {
    if (!el) return false;
    var name = (el.name || '').toLowerCase();
    var id = (el.id || '').toLowerCase();
    var val = (el.value || '').toLowerCase();
    var aria = (el.getAttribute('aria-label') || '').toLowerCase();
    if (name.indexOf('resume') >= 0 || id.indexOf('resume') >= 0 || val.indexOf('resume') >= 0 || aria.indexOf('resume') >= 0) return true;
    if (name.indexOf('cv') >= 0 || id.indexOf('cv') >= 0 || val.indexOf('cv') >= 0 || aria.indexOf('cv') >= 0) return true;
    if (val.indexOf('urn:li:media') >= 0 || val.indexOf('fsd_jobseekingdocument') >= 0 || id.indexOf('fsd_jobseekingdocument') >= 0 || name.indexOf('document') >= 0 || id.indexOf('document') >= 0) return true;

    if (el.closest) {
      if (el.closest('[data-test-document-card], .jobs-document-upload, .jobs-document-upload-redesign, .jobs-resume-picker__resume-list-item, [class*="resume-picker"], [class*="document-upload"], [class*="document-card"], [class*="resume-list"], [class*="resume-item"]')) {
        return true;
      }
      var sec = el.closest('section, fieldset, div[class*="form"], div[class*="step"], div[class*="group"], div[class*="container"]');
      if (sec) {
        var h = sec.querySelector('h1, h2, h3, h4, legend, label, p, span');
        var ht = h ? clean(h.innerText || h.textContent || '').toLowerCase() : '';
        if (ht.indexOf('resume') >= 0 || ht.indexOf('cv') >= 0 || ht.indexOf('السيرة الذاتية') >= 0) {
          if (el.closest('label, li, div') && (el.type === 'radio' || el.type === 'checkbox' || el.getAttribute('role') === 'radio')) {
            return true;
          }
        }
      }
    }

    var p = el;
    for (var i = 0; i < 10 && p; i++, p = p.parentElement) {
      if (!p) break;
      var pClass = (p.className && typeof p.className === 'string') ? p.className.toLowerCase() : '';
      if (pClass.indexOf('resume') >= 0 || pClass.indexOf('document-upload') >= 0 || p.hasAttribute('data-test-document-card')) return true;
      var txt = (p.innerText || p.textContent || '').toLowerCase();
      if (txt.indexOf('last used on') >= 0 || txt.indexOf('be sure to include an updated resume') >= 0 || (txt.indexOf('upload resume') >= 0 && txt.indexOf('pdf') >= 0)) {
        return true;
      }
    }
    return false;
  }

  function triggerRadioSelect(radio) {
    if (!radio) return;
    try {
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked');
      if (setter && setter.set) {
        setter.set.call(radio, true);
      } else {
        radio.checked = true;
      }
      radio.dispatchEvent(new Event('input', { bubbles: true }));
      radio.dispatchEvent(new Event('change', { bubbles: true }));

      var label = null;
      if (radio.id) {
        try {
          var safeId = (window.CSS && CSS.escape) ? CSS.escape(radio.id) : radio.id.replace(/(["\\])/g, '\\$1');
          label = document.querySelector('label[for="' + safeId + '"]');
        } catch (e) {}
      }
      if (!label && radio.closest) label = radio.closest('label');
      if (label) {
        label.click();
        label.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      }

      radio.click();
      radio.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    } catch (e) {
      radio.checked = true;
      try { radio.click(); } catch (err) {}
    }
  }

  function clickElement(el) {
    if (!el) return;
    try { el.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e) {}
    try { el.focus(); } catch (e) {}
    var opts = { bubbles: true, cancelable: true, view: window };
    try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e) {}
    try { el.click(); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('click', opts)); } catch (e) {}
  }

  function getTopResumeCards() {
    var list = document.querySelectorAll('[data-test-document-card], .jobs-document-upload, .jobs-document-upload-redesign, .jobs-resume-picker__resume-list-item, li[class*="resume"], div[class*="resume-item"]');
    var cards = [];
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      var isNested = false;
      for (var j = 0; j < cards.length; j++) {
        if (cards[j].contains(item)) { isNested = true; break; }
      }
      if (!isNested) cards.push(item);
    }
    return cards;
  }

  var lastResumeUploadAttempt = 0;
  function ensureResumeSelected() {
    // Only real upload loaders (NOT role="progressbar" which is LinkedIn's step indicator!)
    var isUploading = document.querySelector('.artdeco-loader, .jobs-document-upload__loading, .jobs-document-upload--loading, [data-test-document-upload-loading], .upload-spinner');
    if (isUploading) {
      return true;
    }

    var cards = getTopResumeCards();
    if (cards && cards.length > 0) {
      // 1. Select the top (most recent) resume
      var firstCard = cards[0];
      var internalRadio = firstCard.querySelector('input[type="radio"], [role="radio"]');
      if (internalRadio) {
        if (!internalRadio.checked) triggerRadioSelect(internalRadio);
      } else {
        var label = firstCard.querySelector('label') || firstCard;
        try { label.click(); } catch (e) {}
        firstCard.setAttribute('aria-checked', 'true');
        firstCard.classList.add('is-selected');
      }

      // Explicitly uncheck and clean outlines on subsequent cards
      for (var k = 1; k < cards.length; k++) {
        var otherCard = cards[k];
        var otherRadio = otherCard.querySelector('input[type="radio"], [role="radio"]');
        if (otherRadio) {
          otherRadio.checked = false;
        }
        otherCard.removeAttribute('aria-checked');
        otherCard.classList.remove('is-selected');
        otherCard.style.outline = 'none';
        otherCard.style.boxShadow = 'none';
        if (otherCard.parentElement) {
          otherCard.parentElement.style.outline = 'none';
          otherCard.parentElement.style.boxShadow = 'none';
        }
      }
      return true;
    }

    // No existing cards on page: click upload button to feed from app
    var uploadBtn = document.querySelector('button[aria-label*="Upload resume" i], button.jobs-document-upload__upload-button, label[for*="upload"], input[type="file"][accept*="pdf"], input[type="file"]');
    var now = Date.now();
    if (uploadBtn && (now - lastResumeUploadAttempt > 15000)) {
      lastResumeUploadAttempt = now;
      try {
        clickElement(uploadBtn);
        return true;
      } catch (e) {}
    }

    var radios = document.querySelectorAll('input[type="radio"]');
    var resumeRadios = [];
    for (var i = 0; i < radios.length; i++) {
      if (isResumeRadio(radios[i])) {
        resumeRadios.push(radios[i]);
      }
    }
    if (resumeRadios.length > 0) {
      var anyChecked = resumeRadios.some(function (r) { return r.checked; });
      if (!anyChecked) {
        triggerRadioSelect(resumeRadios[0]);
      }
      for (var rk = 1; rk < resumeRadios.length; rk++) {
        resumeRadios[rk].checked = false;
        if (resumeRadios[rk].parentElement) {
          resumeRadios[rk].parentElement.style.outline = 'none';
          resumeRadios[rk].parentElement.style.boxShadow = 'none';
        }
      }
      return true;
    }

    return false;
  }

  function hasExistingResume() {
    var radios = document.querySelectorAll('input[type="radio"]');
    for (var i = 0; i < radios.length; i++) {
      if (isResumeRadio(radios[i])) return true;
    }
    if (document.querySelector('[data-test-document-card], .jobs-document-upload, [data-test-document-title], [class*="resume"], [class*="cv"]')) {
      return true;
    }
    return false;
  }

  // A radio or checkbox drawn by the site (the input itself hidden) counts when its label shows.
  function shownByLabel(el) {
    var lab = el.closest('label');
    if (!lab && el.id) {
      try { lab = document.querySelector('label[for="' + (window.CSS && CSS.escape ? CSS.escape(el.id) : el.id) + '"]'); } catch (e) { lab = null; }
    }
    return !!(lab && visible(lab));
  }
  // A drop-down the site builds itself (role="combobox"): typing filters it, then the matching option is picked.
  function isCombo(el) {
    return el.getAttribute('role') === 'combobox' || !!el.getAttribute('aria-autocomplete')
      || (el.getAttribute('aria-haspopup') || '') === 'listbox';
  }
  function pickOption(el, want, tries) {
    var box = el.getAttribute('aria-controls') || el.getAttribute('aria-owns');
    var scope = (box && document.getElementById(box)) || document;
    var opts = Array.prototype.filter.call(scope.querySelectorAll('[role="option"]'), visible);
    var hit = opts.filter(function (o) { return same(textOf(o), want); })[0]
      || opts.filter(function (o) { return textOf(o).toLowerCase().indexOf(String(want).toLowerCase()) >= 0; })[0];
    if (hit) { clickElement(hit); mark(el.closest('[class*="select" i], [class*="control" i]') || el); return; }
    if (tries > 0) setTimeout(function () { pickOption(el, want, tries - 1); }, 250);
  }

  var fields = [];
  function collect() {
    ensureResumeSelected();
    fields = [];
    var out = [];
    var groups = {};
    var els = document.querySelectorAll('input, select, textarea');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var type = (el.getAttribute('type') || el.tagName).toLowerCase();
      if (el.disabled || el.readOnly || SKIP.test(type)) continue;
      if (!visible(el) && !((type === 'radio' || type === 'checkbox') && shownByLabel(el))) continue;
      if (isResumeRadio(el)) {
        continue;
      }
      if (type === 'radio' || type === 'checkbox') {
        var name = el.name || el.id || ('aa' + i);
        var g = groups[type + ':' + name];
        if (!g) {
          g = groups[type + ':' + name] = { type: type, els: [], name: name };
        }
        g.els.push(el);
        continue;
      }
      var rawLabel = '';
      if (el.id) {
        try {
          var safeId = (window.CSS && CSS.escape) ? CSS.escape(el.id) : el.id.replace(/(["\\])/g, '\\$1');
          var lblEl = document.querySelector('label[for="' + safeId + '"]');
          if (lblEl) rawLabel = textOf(lblEl);
        } catch (e) {}
      }
      var lbl = rawLabel || labelOf(el);
      var isNum = isNumericInput(el, lbl);
      if (el.tagName === 'INPUT' && isCombo(el)) {
        var lb = el.getAttribute('aria-controls') && document.getElementById(el.getAttribute('aria-controls'));
        var copts = lb ? Array.prototype.map.call(lb.querySelectorAll('[role="option"]'), textOf).filter(Boolean) : [];
        var cf = { key: 'f' + fields.length, label: lbl, kind: copts.length ? 'select' : 'text', options: copts,
          value: String(el.value || ''), placeholder: clean(el.getAttribute('placeholder') || ''), maxlength: 0,
          required: isRequired(el, lbl), combo: true };
        fields.push({ f: cf, els: [el] });
        out.push(cf);
        continue;
      }
      var f = {
        key: 'f' + fields.length,
        label: lbl,
        kind: el.tagName === 'SELECT' ? 'select' : el.tagName === 'TEXTAREA' ? 'textarea'
          : isNum ? 'number' : type === 'email' ? 'email' : type === 'tel' ? 'tel' : type === 'number' ? 'number' : type === 'url' ? 'url'
            : type === 'date' ? 'date' : 'text',
        options: el.tagName === 'SELECT' ? Array.prototype.map.call(el.options, function (o) { return clean(o.text); })
          .filter(function (t) { return t && !/^(select|choose|please select|--)/i.test(t); }) : [],
        value: el.tagName === 'SELECT' ? (el.selectedIndex > 0 ? clean(el.options[el.selectedIndex].text) : '') : String(el.value || ''),
        placeholder: clean(el.getAttribute('placeholder') || ''),
        maxlength: el.maxLength > 0 ? el.maxLength : 0,
        required: isRequired(el, lbl),
        error: errorOf(el),
      };
      fields.push({ f: f, els: [el] });
      out.push(f);
    }
    Object.keys(groups).forEach(function (k) {
      var g = groups[k];
      var opts = g.els.map(optionLabel);
      var label = g.type === 'checkbox' && g.els.length === 1 ? labelOf(g.els[0]) : groupLabel(g.els[0]);
      var f = {
        key: 'f' + fields.length,
        label: label,
        kind: g.type === 'radio' ? 'radio' : (g.els.length === 1 ? 'checkbox' : 'multi'),
        options: g.type === 'checkbox' && g.els.length === 1 ? [] : opts,
        value: g.els.filter(function (e) { return e.checked; }).map(optionLabel).join(', '),
        placeholder: '',
        maxlength: 0,
        required: isRequired(g.els[0], label),
        error: errorOf(g.els[0].closest('fieldset') ? g.els[0] : g.els[0]),
      };
      fields.push({ f: f, els: g.els });
      out.push(f);
    });
    return JSON.stringify(out);
  }

  function setValue(el, v) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    var str = Array.isArray(v) ? v.join(', ') : String(v == null ? '' : v);
    var type = (el.getAttribute('type') || '').toLowerCase();
    var mode = (el.getAttribute('inputmode') || el.inputMode || '').toLowerCase();
    if (type === 'number' || mode === 'numeric' || mode === 'decimal' || isNumericInput(el, labelOf(el))) {
      str = str.replace(/[^0-9.]/g, '');
    }
    if (desc && desc.set) desc.set.call(el, str); else el.value = str;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    if (!isCombo(el)) el.dispatchEvent(new Event('blur', { bubbles: true }));
  }
  function same(a, b) {
    a = clean(a).toLowerCase();
    b = clean(b).toLowerCase();
    return a === b || (a && b && (a.indexOf(b) === 0 || b.indexOf(a) === 0));
  }
  function mark(el) {
    if (!el || isResumeRadio(el)) return;
    el.style.outline = '2px solid #11805b';
    el.style.outlineOffset = '1px';
  }

  function apply(values) {
    var n = 0;
    fields.forEach(function (item) {
      var v = values[item.f.key];
      if (v === undefined || v === null) return;
      var f = item.f;
      var el = item.els[0];
      if (f.combo) {
        var want0 = Array.isArray(v) ? v[0] : v;
        try { el.focus(); } catch (e) { /* not focusable */ }
        setValue(el, String(want0));
        el.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        pickOption(el, want0, 6);
        n++;
        return;
      }
      if (f.kind === 'select') {
        var want = Array.isArray(v) ? v[0] : v;
        for (var i = 0; i < el.options.length; i++) {
          if (same(el.options[i].text, want) || same(el.options[i].value, want)) {
            el.selectedIndex = i;
            el.dispatchEvent(new Event('change', { bubbles: true }));
            mark(el);
            n++;
            return;
          }
        }
      } else if (f.kind === 'radio' || f.kind === 'multi') {
        var wants = Array.isArray(v) ? v : [v];
        var hit = false;
        item.els.forEach(function (r) {
          if (wants.some(function (w) { return same(optionLabel(r), w); }) && !r.checked) {
            if (visible(r)) r.click();
            else { var lab = r.closest('label') || (r.id && document.querySelector('label[for="' + r.id + '"]')); (lab || r).click(); }
            hit = true;
          }
        });
        if (hit) { mark(item.els[0].closest('fieldset') || item.els[0].parentElement); n++; }
      } else if (f.kind === 'checkbox') {
        if (v && !el.checked) { el.click(); mark(el); n++; }
      } else {
        setValue(el, Array.isArray(v) ? v.join(', ') : String(v));
        mark(el);
        n++;
      }
    });
    try {
      if (document.activeElement && document.activeElement.blur && !isCombo(document.activeElement)) {
        document.activeElement.blur();
      }
    } catch (e) {}
    return n;
  }

  // Application forms that live in a frame from another site (Greenhouse, Lever...): the app opens the frame itself.
  var ATS = /greenhouse\.io|lever\.co|workable\.com|smartrecruiters\.com|ashbyhq\.com|bamboohr\.com|jobvite\.com|icims\.com|myworkdayjobs\.com|recruitee\.com|breezy\.hr|teamtailor\.com|personio\.|zohorecruit|jazzhr|applytojob\.com/i;
  function frames() {
    var out = [];
    var list = document.querySelectorAll('iframe[src]');
    for (var i = 0; i < list.length; i++) {
      var src = list[i].src || '';
      if (/^https:\/\//i.test(src) && ATS.test(src) && visible(list[i])) out.push(src);
    }
    return JSON.stringify(out);
  }

  // The page as text, for the job's fit with your CV, and whether it asks for a file (your CV).
  function page() {
    var body = document.body ? (document.body.innerText || '') : '';
    return JSON.stringify({
      title: clean(document.title || ''),
      heading: textOf(document.querySelector('h1')),
      text: body.replace(/\n{3,}/g, '\n\n').slice(0, 9000),
      file: !!document.querySelector('input[type="file"]'),
      url: location.href,
    });
  }

  // ---------------------------------------------------------------- Fill and review
  // The site's own "Apply" button, when the job page shows the form only after it. Never a "Submit" button, and never
  // "Apply with LinkedIn / Indeed / Google" (those are other sites' sign-ins).
  var START = /^(easy apply( on linkedin)?|apply( now| for( (this|the|a))? (job|position|role|this position))?|apply to (this )?job|start (your )?application|apply here|i'?m interested|apply online|تقدم للوظيفة|قدم الآن|التقديم للوظيفة|قدّم الآن)$/i;
  var OTHER = /indeed|facebook|seek|glassdoor|resume ?parse|upload resume to autofill/i;
  function labelText(el) {
    return clean(el.innerText || el.value || el.getAttribute('aria-label') || '');
  }
  function startButton() {
    var liBtn = document.querySelector(".jobs-apply-button--top-card button, .jobs-s-apply button, button.jobs-apply-button, button[aria-label*='Easy Apply'], a[aria-label*='Easy Apply'], a[href*='openSDUIApplyFlow']");
    if (liBtn && visible(liBtn)) return liBtn;

    var wzBtn = document.querySelector('button[class*="apply" i], a[href*="/apply/" i], button[data-test-id*="apply" i], button[aria-label*="Apply" i]');
    if (wzBtn && visible(wzBtn) && START.test(labelText(wzBtn))) return wzBtn;

    var list = document.querySelectorAll('a, button, [role="button"], input[type="button"]');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      var t = labelText(el);
      if (t && t.length < 40 && START.test(t) && !OTHER.test(t) && visible(el) && !el.closest('form')) return el;
    }
    return null;
  }
  function clickStart() {
    var b = startButton();
    if (!b) return '';
    var t = labelText(b);
    b.click();
    return t;
  }

  // Required fields that are still empty after filling: marked in orange, for you.
  function isEmpty(item) {
    var el = item.els[0];
    if (item.f.kind === 'radio' || item.f.kind === 'multi') return !item.els.some(function (e) { return e.checked; });
    if (item.f.kind === 'checkbox') return !el.checked;
    if (item.f.kind === 'select') return el.selectedIndex <= 0 && !clean(el.value);
    return !String(el.value || '').trim();
  }
  function fileWanted() {
    if (hasExistingResume()) {
      ensureResumeSelected();
      return false;
    }
    var f = document.querySelectorAll('input[type="file"]');
    for (var i = 0; i < f.length; i++) {
      if (visible(f[i]) && !(f[i].files && f[i].files.length)) return true;
    }
    return false;
  }
  function missing() {
    var out = [];
    fields.forEach(function (item) {
      if (!item.f.required || !isEmpty(item)) return;
      var box = item.els.length > 1 ? (item.els[0].closest('fieldset') || item.els[0].parentElement) : item.els[0];
      box.style.outline = '2px solid #d97706';
      box.style.outlineOffset = '1px';
      out.push(item.f.label);
    });
    return out;
  }
  function findModalButtons() {
    ensureResumeSelected();
    var allBtns = document.querySelectorAll('button, input[type="submit"], [role="button"], a[role="button"]');
    var res = { next: null, review: null, submit: null };
    for (var i = 0; i < allBtns.length; i++) {
      var b = allBtns[i];
      if (b.disabled || !visible(b)) continue;
      var t = labelText(b).toLowerCase();
      var aria = clean(b.getAttribute('aria-label') || '').toLowerCase();
      var hasEasyNext = b.hasAttribute('data-easy-apply-next-button');

      // Ignore dismiss, close, or job alert toast buttons
      if (/dismiss|close|manage alerts|إلغاء|إغلاق/.test(t) || /dismiss|close/.test(aria)) continue;

      if (/submit application|submit|إرسال|ارسال التقديم|تأكيد التقديم/i.test(t) || /submit application|submit/i.test(aria)) {
        if (!res.submit) res.submit = b;
      } else if (/review application|review your application|review|مراجعة/i.test(t) || /review application|review your application|review/i.test(aria)) {
        if (!res.review) res.review = b;
      } else if (hasEasyNext || /next( step)?|continue|save and continue|التالي|متابعة/i.test(t) || /next|continue/i.test(aria)) {
        if (!res.next) res.next = b;
      }
    }
    return res;
  }

  function review() {
    ensureResumeSelected();
    var btns = findModalButtons();
    var bType = btns.submit ? 'submit' : btns.review ? 'review' : btns.next ? 'next' : 'none';
    var refused = fields.map(function (item) {
      var e = errorOf(item.els[0]);
      return e ? item.f.label + ': ' + e : '';
    }).filter(Boolean);
    return JSON.stringify({
      errors: refused,
      missing: missing(),
      file: fileWanted(),
      submit: !!(btns.submit || btns.review || btns.next || submitButton()),
      button_type: bType,
      has_resume: hasExistingResume()
    });
  }
  var nextAt = 0;
  function next() {
    var left = fields.filter(function (item) { return item.f.required && isEmpty(item); });
    if (!left.length) return '';
    var item = left[nextAt % left.length];
    nextAt++;
    var el = item.els[0];
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    try { el.focus({ preventScroll: true }); } catch (e) { /* not focusable */ }
    return item.f.label;
  }
  var SUBMIT = /^(submit( (my )?application)?|send( (my )?application)?|review( (my )?application)?|next|continue|apply|finish|complete( application)?)$/i;
  function submitButton() {
    var liSubmit = document.querySelector("button[aria-label*='Submit application'], button[aria-label*='Review'], button.jobs-apply-button[data-easy-apply-next-button]");
    if (liSubmit && visible(liSubmit)) return liSubmit;

    var list = document.querySelectorAll('button, input[type="submit"], [role="button"]');
    var best = null;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!visible(el)) continue;
      var t = labelText(el);
      if (el.type === 'submit' || (t.length < 40 && SUBMIT.test(t))) best = el;   // the last one: forms end with it
    }
    return best;
  }
  function highlightSubmit(b) {
    if (!b) return;
    b.scrollIntoView({ block: 'center', behavior: 'smooth' });
    b.style.outline = '4px solid #00c853';
    b.style.outlineOffset = '3px';
    b.style.boxShadow = '0 0 20px rgba(0,200,83,0.8)';
  }
  function toSubmit() {
    ensureResumeSelected();
    var btns = findModalButtons();
    if (btns.submit) {
      highlightSubmit(btns.submit);
      return true;
    }
    if (btns.review) {
      btns.review.click();
      return true;
    }
    if (btns.next) {
      btns.next.click();
      return true;
    }
    var b = submitButton();
    if (!b) return false;
    highlightSubmit(b);
    return true;
  }
  function advance() {
    ensureResumeSelected();
    var isUploading = document.querySelector('.artdeco-loader, .jobs-document-upload__loading, .jobs-document-upload--loading, [data-test-document-upload-loading], .upload-spinner');
    if (isUploading) {
      return 'uploading';
    }
    var btns = findModalButtons();
    if (btns.next && !btns.next.disabled) {
      clickElement(btns.next);
      return 'next';
    }
    if (btns.review && !btns.review.disabled) {
      clickElement(btns.review);
      return 'review';
    }
    if (btns.submit && !btns.submit.disabled) {
      highlightSubmit(btns.submit);
      return 'ready_submit';
    }
    if (btns.next) {
      clickElement(btns.next);
      return 'next';
    }
    if (btns.review) {
      clickElement(btns.review);
      return 'review';
    }
    return 'none';
  }
  function doSubmit() {
    var btns = findModalButtons();
    var b = btns.submit || submitButton();
    if (b) {
      b.click();
      return true;
    }
    return false;
  }

  // The site's "thank you" after you sent it: the app marks the job Applied.
  var THANKS = /thank(s| you) for (applying|your application)|application (has been |was )?(received|submitted|sent)|we('ve| have) received your application|successfully (submitted|applied)|your application is (complete|in)|applied to|application sent|easy apply submitted|you('ve| have) (successfully )?applied|application (was )?successful|تم إرسال طلبك|تم التقديم بنجاح|تم تقديم طلبك/i;
  function done() {
    var modal = document.querySelector('.jobs-easy-apply-modal, [role="dialog"], .artdeco-modal');
    var modalText = modal ? clean(modal.innerText) : '';
    var body = document.body ? (document.body.innerText || '') : '';
    var open = document.querySelectorAll('form input:not([type="hidden"]), form textarea');
    var openVisible = Array.prototype.filter.call(open, visible).length;
    return (THANKS.test(modalText) || THANKS.test(body.slice(0, 20000))) && openVisible < 2;
  }

  function focusQuestion(label) {
    if (!label) return;
    var target = null;
    for (var i = 0; i < fields.length; i++) {
      var it = fields[i];
      if (same(it.f.label, label) || (it.f.label && it.f.label.indexOf(label) >= 0) || (label.indexOf(it.f.label) >= 0)) {
        target = it.els[0];
        break;
      }
    }
    if (!target) {
      var inputs = document.querySelectorAll('input, select, textarea');
      for (var j = 0; j < inputs.length; j++) {
        var inp = inputs[j];
        if (same(labelOf(inp), label)) { target = inp; break; }
      }
    }
    if (target) {
      target.scrollIntoView({ block: 'center', behavior: 'smooth' });
      try { target.focus({ preventScroll: true }); } catch (e) {}
      target.style.outline = '3px solid #ff9800';
      target.style.boxShadow = '0 0 14px rgba(255,152,0,0.6)';
      setTimeout(function() {
        target.style.outline = '';
        target.style.boxShadow = '';
      }, 4000);
    }
  }

  function showFloatingPill(title, sub, onAct) {
    try {
      var old = document.getElementById('aa-floating-pill');
      if (old) old.remove();
      var pill = document.createElement('div');
      pill.id = 'aa-floating-pill';
      pill.style.cssText = 'position:fixed;top:16px;left:16px;right:16px;z-index:9999999;background:#111a2b;color:#ffffff;border-radius:16px;padding:12px 16px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:space-between;gap:12px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;border:1px solid #2a3a55;';
      pill.innerHTML = '<div style="flex:1;min-width:0;">'
        + '<div style="font-size:12px;font-weight:700;color:#38bdf8;margin-bottom:2px;">✨ ' + clean(title || 'Question Needed') + '</div>'
        + '<div style="font-size:14px;color:#e2e8f0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + clean(sub || '') + '</div>'
        + '</div>'
        + '<button type="button" style="background:#2563eb;color:#fff;border:none;border-radius:10px;padding:8px 16px;font-size:13px;font-weight:700;cursor:pointer;flex-shrink:0;">Answer</button>';
      var btn = pill.querySelector('button');
      var handleAct = function(e) {
        if (e) e.stopPropagation();
        pill.remove();
        if (onAct) onAct();
      };
      if (btn) btn.onclick = handleAct;
      pill.onclick = handleAct;
      document.body.appendChild(pill);
      setTimeout(function() { if (pill.parentNode) pill.remove(); }, 8000);
    } catch (e) {}
  }
  window.showPopupPill = function(opt) {
    if (!opt) return;
    showFloatingPill(opt.title, opt.sub, opt.onAct);
  };

  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('button, a, [role="button"]');
    if (!b) return;
    var t = labelText(b).toLowerCase();
    if (/next|continue|review/.test(t)) {
      setTimeout(function () {
        if (window.AAFillHost && window.AAFillHost.onStepChange) {
          window.AAFillHost.onStepChange();
        }
      }, 700);
    }
  }, true);

  window.AAFill = { collect: collect, apply: apply, frames: frames, page: page, clickStart: clickStart, review: review,
    next: next, toSubmit: toSubmit, advance: advance, doSubmit: doSubmit, done: done, focusQuestion: focusQuestion, showFloatingPill: showFloatingPill };
}());
