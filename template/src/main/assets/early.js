(function () {
  if (window.__w2a_early) return;
  window.__w2a_early = 1;

  // Pretend to be an installed app (display-mode: standalone), so sites hide their own "install" banners.
  try {
    var mm = window.matchMedia.bind(window);
    window.matchMedia = function (q) {
      var r = mm(q);
      if (/display-mode\s*:\s*(standalone|fullscreen|minimal-ui|browser)/i.test(String(q))) {
        return new Proxy(r, {
          get: function (t, k) {
            if (k === 'matches') return /standalone/i.test(String(q));
            var v = t[k];
            return typeof v === 'function' ? v.bind(t) : v;
          }
        });
      }
      return r;
    };
  } catch (e) {}

  try {
    Object.defineProperty(navigator, 'standalone', { get: function () { return true; } });
  } catch (e) {}

  // Swallow the browser install-prompt event so sites cannot show their own button for it.
  try {
    window.addEventListener('beforeinstallprompt', function (e) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }, true);
  } catch (e) {}
})();
