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

  function isNumericInput(el, label) {
    var type = (el.getAttribute('type') || el.tagName).toLowerCase();
    var mode = (el.getAttribute('inputmode') || el.inputMode || '').toLowerCase();
    if (type === 'number' || mode === 'numeric' || mode === 'decimal') return true;
    var lab = String(label || '').toLowerCase();
    return /\b(how many (years|months|days)|years of experience|how long have you|gross salary|expected salary|current salary|salary|compensation|notice period in days|gpa)\b/i.test(lab)
      || /\b(experience|salary|gross|pay|ctc|wage)\b/i.test(lab);
  }

  function isResumeRadio(el) {
    if (!el) return false;
    var type = (el.getAttribute('type') || el.tagName || '').toLowerCase();
    if (type !== 'radio' && el.getAttribute('role') !== 'radio') {
      var isCard = el.hasAttribute('data-test-document-card') || el.classList.contains('jobs-document-upload') || el.classList.contains('jobs-resume-picker__resume-list-item');
      if (!isCard) return false;
    }
    var name = (el.name || '').toLowerCase();
    var id = (el.id || '').toLowerCase();
    var val = (el.value || '').toLowerCase();
    var aria = (el.getAttribute('aria-label') || '').toLowerCase();
    if (name.indexOf('resume') >= 0 || id.indexOf('resume') >= 0 || val.indexOf('resume') >= 0 || aria.indexOf('resume') >= 0) return true;
    if (name.indexOf('cv') >= 0 || id.indexOf('cv') >= 0 || val.indexOf('cv') >= 0 || aria.indexOf('cv') >= 0) return true;
    if (val.indexOf('urn:li:media') >= 0 || val.indexOf('fsd_jobseekingdocument') >= 0 || name.indexOf('document') >= 0 || id.indexOf('document') >= 0) return true;
    if (el.closest) {
      if (el.closest('[data-test-document-card], [data-test-document-title], [class*="document-upload"], [class*="resume"], [id*="resume"], [id*="document"], fieldset[class*="resume"], div[class*="resume"]')) return true;
      var sec = el.closest('section, fieldset, div[class*="form"], div[class*="step"], div[class*="group"], div[class*="container"]');
      if (sec) {
        var h = sec.querySelector('h1, h2, h3, h4, legend, label, p, span');
        var ht = h ? clean(h.innerText || h.textContent || '').toLowerCase() : '';
        if (ht.indexOf('resume') >= 0 || ht.indexOf('cv') >= 0) return true;
      }
    }
    var p = el.parentElement;
    for (var i = 0; i < 5 && p; i++, p = p.parentElement) {
      if (p.querySelector && p.querySelector('button[aria-label*="Upload resume" i], .jobs-document-upload__upload-button, [data-test-document-card]')) {
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
        label = document.querySelector('label[for="' + (window.CSS && CSS.escape ? CSS.escape(radio.id) : radio.id) + '"]');
      }
      if (!label && radio.closest) label = radio.closest('label');
      if (label) {
        label.click();
        label.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      }

      radio.click();
      radio.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));

      var card = radio.closest ? radio.closest('[data-test-document-card], .jobs-document-upload, [role="radio"]') : null;
      if (card && card !== radio && card !== label) {
        card.setAttribute('aria-checked', 'true');
        card.classList.add('is-selected');
        card.click();
      }
    } catch (e) {
      radio.checked = true;
      try { radio.click(); } catch (err) {}
    }
  }

  function getTopResumeCards() {
    var list = document.querySelectorAll('[data-test-document-card], .jobs-document-upload, .jobs-resume-picker__resume-list-item');
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
    // 1. First priority: Upload the registered CV from the app!
    var uploadBtn = document.querySelector('button[aria-label*="Upload resume" i], button.jobs-document-upload__upload-button, label[for*="upload"], input[type="file"][accept*="pdf"], input[type="file"]');
    var now = Date.now();
    
    // Check if an upload is actively in progress
    var isUploading = document.querySelector('.artdeco-loader, [role="progressbar"], .jobs-document-upload__loading, .jobs-document-upload--loading');
    if (isUploading) {
      return true;
    }

    // If uploadBtn exists and we haven't triggered it in the last 15 seconds:
    if (uploadBtn && (now - lastResumeUploadAttempt > 15000)) {
      lastResumeUploadAttempt = now;
      try {
        uploadBtn.click();
        return true;
      } catch (e) { /* fallback to selecting */ }
    }

    // 2. Fallback: Select ONLY the first (most recent) resume card/radio
    var cards = getTopResumeCards();
    if (cards.length > 0) {
      var firstCard = cards[0];
      var internalRadio = firstCard.querySelector('input[type="radio"]');
      if (internalRadio) {
        if (!internalRadio.checked) triggerRadioSelect(internalRadio);
      } else {
        var label = firstCard.querySelector('label') || firstCard;
        label.click();
        firstCard.setAttribute('aria-checked', 'true');
        firstCard.classList.add('is-selected');
      }

      // Explicitly uncheck and un-mark any subsequent cards
      for (var k = 1; k < cards.length; k++) {
        var otherCard = cards[k];
        var otherRadio = otherCard.querySelector('input[type="radio"]');
        if (otherRadio) otherRadio.checked = false;
        otherCard.removeAttribute('aria-checked');
        otherCard.classList.remove('is-selected');
        otherCard.style.outline = 'none';
      }
      return true;
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
    if (document.querySelector('[data-test-document-card], .jobs-document-upload, [data-test-document-title]')) {
      return true;
    }
    return false;
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
      if (el.disabled || el.readOnly || SKIP.test(type) || !visible(el)) continue;
      if (type === 'radio' && isResumeRadio(el)) {
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
      var rawLabel = (el.id && document.querySelector('label[for="' + el.id + '"]')) ? document.querySelector('label[for="' + el.id + '"]').innerText : '';
      var lbl = rawLabel || labelOf(el);
      var isNum = isNumericInput(el, lbl);
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
    el.dispatchEvent(new Event('blur', { bubbles: true }));
  }
  function same(a, b) {
    a = clean(a).toLowerCase();
    b = clean(b).toLowerCase();
    return a === b || (a && b && (a.indexOf(b) === 0 || b.indexOf(a) === 0));
  }
  function mark(el) {
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
            r.click();
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
  var START = /^(easy apply( on linkedin)?|apply( now| for (this|the) (job|position|role))?|start (your )?application|apply here|i'?m interested|apply online)$/i;
  var OTHER = /indeed|facebook|seek|glassdoor|resume ?parse|upload resume to autofill/i;
  function labelText(el) {
    return clean(el.innerText || el.value || el.getAttribute('aria-label') || '');
  }
  function startButton() {
    var liBtn = document.querySelector(".jobs-apply-button--top-card button, .jobs-s-apply button, button.jobs-apply-button, button[aria-label*='Easy Apply'], a[aria-label*='Easy Apply'], a[href*='openSDUIApplyFlow']");
    if (liBtn && visible(liBtn)) return liBtn;

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
    var modal = document.querySelector('.jobs-easy-apply-modal, [role="dialog"], .modal-content, .artdeco-modal, body');
    var btns = (modal || document).querySelectorAll('button, input[type="submit"], [role="button"]');
    var res = { next: null, review: null, submit: null };
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      if (!visible(b)) continue;
      var t = labelText(b).toLowerCase();
      var aria = clean(b.getAttribute('aria-label') || '').toLowerCase();
      var hasEasyNext = b.hasAttribute('data-easy-apply-next-button');
      if (/submit application|submit/.test(t) || /submit application|submit/.test(aria)) {
        res.submit = b;
      } else if (/review application|review your application|review/.test(t) || /review application|review your application|review/.test(aria)) {
        res.review = b;
      } else if (hasEasyNext || /next|continue/.test(t) || /next|continue/.test(aria)) {
        res.next = b;
      }
    }
    return res;
  }

  function review() {
    ensureResumeSelected();
    var btns = findModalButtons();
    var bType = btns.submit ? 'submit' : btns.review ? 'review' : btns.next ? 'next' : 'none';
    return JSON.stringify({
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
    var isUploading = document.querySelector('.artdeco-loader, [role="progressbar"], .jobs-document-upload__loading, .jobs-document-upload--loading');
    if (isUploading) {
      return 'uploading';
    }
    var btns = findModalButtons();
    if (btns.next && !btns.next.disabled) {
      btns.next.click();
      return 'next';
    }
    if (btns.review && !btns.review.disabled) {
      btns.review.click();
      return 'review';
    }
    if (btns.submit && !btns.submit.disabled) {
      highlightSubmit(btns.submit);
      return 'ready_submit';
    }
    if (btns.next) {
      btns.next.click();
      return 'next';
    }
    if (btns.review) {
      btns.review.click();
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
  var THANKS = /thank(s| you) for (applying|your application)|application (has been |was )?(received|submitted|sent)|we('ve| have) received your application|successfully (submitted|applied)|your application is (complete|in)|applied to|application sent|easy apply submitted/i;
  function done() {
    var modal = document.querySelector('.jobs-easy-apply-modal, [role="dialog"], .artdeco-modal');
    var modalText = modal ? clean(modal.innerText) : '';
    var body = document.body ? (document.body.innerText || '') : '';
    var open = document.querySelectorAll('form input:not([type="hidden"]), form textarea');
    var openVisible = Array.prototype.filter.call(open, visible).length;
    return (THANKS.test(modalText) || THANKS.test(body.slice(0, 20000))) && openVisible < 2;
  }

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
    next: next, toSubmit: toSubmit, advance: advance, doSubmit: doSubmit, done: done };
}());
