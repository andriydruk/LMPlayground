// Shared behaviour for the site: language, theme toggle, nav hairline, reveal.

/* ===== Language =====
   lang.js (loaded in <head>) picks LMP_LANG and loads its strings. Static text is
   marked with data-i18n / data-i18n-html / data-i18n-attr and the English copy
   stays in the HTML; scripts read strings through lmpT(). */
var lmpLang = window.LMP_LANG || 'en', lmpLocale = 'en-US';
function lmpIso(code) { return { iw: 'he', 'in': 'id', zh: 'zh-CN', pt: 'pt-BR' }[code] || code; }
function lmpT(key, fallback) {
  var B = window.LMP_I18N || {}, v = (B[lmpLang] || {})[key];
  if (v == null) v = (B.en || {})[key];
  return v == null ? (fallback != null ? fallback : key) : v;
}
// "**bold**" and line breaks, as used by the app's FAQ strings.
function lmpMd(str) {
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/\n/g, '<br/>');
}
function lmpTok(n) { return lmpT('units.tokens', '%d tokens').replace('%d', Number(n).toLocaleString(lmpLocale)); }
function lmpThinking(secs, tokens) { return lmpT('phone.thinking', 'Thinking') + ' · ' + secs + 's · ' + lmpTok(tokens); }
function lmpStats(tokens, secs) { return lmpTok(tokens) + ' · ' + secs + 's · ' + Math.round(tokens / secs) + ' tok/s'; }
(function () {
  lmpLocale = lmpIso(lmpLang);

  function apply() {
    document.querySelectorAll('[data-i18n]').forEach(function (el) { el.textContent = lmpT(el.getAttribute('data-i18n'), el.textContent); });
    document.querySelectorAll('[data-i18n-html]').forEach(function (el) { el.innerHTML = lmpT(el.getAttribute('data-i18n-html'), el.innerHTML); });
    document.querySelectorAll('[data-i18n-attr]').forEach(function (el) {
      el.getAttribute('data-i18n-attr').split(';').forEach(function (pair) {
        var kv = pair.split(':'); el.setAttribute(kv[0], lmpT(kv[1], el.getAttribute(kv[0])));
      });
    });
    var title = document.querySelector('meta[name="lmp-title-key"]');
    if (title) document.title = lmpT(title.content, document.title);
  }
  // Switching reloads with ?lang= so every animated part starts in the new language.
  window.lmpSetLang = function (code) {
    try { localStorage.setItem('lmpLang', code); } catch (e) {}
    var u = new URL(location.href); u.searchParams.set('lang', code); location.href = u.toString();
  };
  if (lmpLang !== 'en') apply();
  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[data-lang]');
    if (a) { e.preventDefault(); lmpSetLang(a.getAttribute('data-lang')); }
  });
  var sel = document.getElementById('langSelect');
  if (sel) {
    sel.innerHTML = LMP_LANGS.map(function (l) { return '<option value="' + l[0] + '"' + (l[0] === lmpLang ? ' selected' : '') + '>' + l[1] + '</option>'; }).join('');
    sel.addEventListener('change', function () { lmpSetLang(sel.value); });
  }
})();

(function () {
  var root = document.documentElement;
  var btn = document.getElementById('themeBtn');
  if (btn) btn.addEventListener('click', function () {
    var cur = root.getAttribute('data-theme') ||
      (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    var next = cur === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('lmp-theme', next); } catch (e) {}
  });

  var nav = document.getElementById('nav');
  if (nav) {
    var on = function () { nav.classList.toggle('scrolled', scrollY > 8); };
    addEventListener('scroll', on, { passive: true }); on();
  }

  var els = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window)) { els.forEach(function (e) { e.classList.add('in'); }); return; }
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } });
  }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  els.forEach(function (e) { io.observe(e); });
})();

// Runs fn when el first scrolls into view (or right away without IO support).
window.lmpWhenVisible = function (el, fn, threshold) {
  if (!el) return;
  if (!('IntersectionObserver' in window)) { fn(); return; }
  var io = new IntersectionObserver(function (entries) {
    if (entries[0].isIntersecting) { io.disconnect(); fn(); }
  }, { threshold: threshold || 0.3 });
  io.observe(el);
};
window.lmpReduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
