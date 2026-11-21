// Picks the page language and loads its strings before the page scripts run.
// Codes mirror the Android resource folders (iw = Hebrew, in = Indonesian).
// Order: ?lang=xx → saved choice → browser languages → English.
var LMP_LANGS = [
  ['en', 'English'], ['ar', 'العربية'], ['bn', 'বাংলা'], ['cs', 'Čeština'], ['da', 'Dansk'],
  ['de', 'Deutsch'], ['es', 'Español'], ['fi', 'Suomi'], ['fil', 'Filipino'], ['fr', 'Français'],
  ['hi', 'हिन्दी'], ['in', 'Bahasa Indonesia'], ['it', 'Italiano'], ['iw', 'עברית'], ['ja', '日本語'],
  ['ko', '한국어'], ['ms', 'Bahasa Melayu'], ['nb', 'Norsk'], ['nl', 'Nederlands'], ['pl', 'Polski'],
  ['pt', 'Português'], ['ro', 'Română'], ['sv', 'Svenska'], ['th', 'ไทย'], ['tr', 'Türkçe'],
  ['uk', 'Українська'], ['vi', 'Tiếng Việt'], ['zh', '简体中文']
];
var LMP_LANG = 'en';
(function () {
  var codes = LMP_LANGS.map(function (l) { return l[0]; });
  function norm(c) {
    var b = String(c || '').toLowerCase().split(/[-_]/)[0];
    b = { he: 'iw', id: 'in', no: 'nb', nn: 'nb', tl: 'fil' }[b] || b;
    return codes.indexOf(b) >= 0 ? b : null;
  }
  var pick = null;
  try { pick = norm(new URLSearchParams(location.search).get('lang')); } catch (e) {}
  if (!pick) { try { pick = norm(localStorage.getItem('lmpLang')); } catch (e) {} }
  if (!pick) (navigator.languages || [navigator.language]).some(function (l) { return (pick = norm(l)); });
  LMP_LANG = pick || 'en';
  var root = document.documentElement;
  root.lang = { iw: 'he', 'in': 'id', zh: 'zh-CN', pt: 'pt-BR' }[LMP_LANG] || LMP_LANG;
  root.dir = (LMP_LANG === 'ar' || LMP_LANG === 'iw') ? 'rtl' : 'ltr';
  var base = document.currentScript.src.replace(/lang\.js(\?.*)?$/, '');
  document.write('<script src="' + base + 'i18n/en.js"><\/script>');
  if (LMP_LANG !== 'en') document.write('<script src="' + base + 'i18n/' + LMP_LANG + '.js"><\/script>');
})();
