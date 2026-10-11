(function () {
  if (window.__w2a || typeof W2A === 'undefined') return;
  window.__w2a = 1;

  var cv = document.createElement('canvas');
  cv.width = cv.height = 1;
  var cx = cv.getContext('2d', { willReadFrequently: true });

  function rgb(c) {
    if (!c || c === 'transparent') return null;
    cx.clearRect(0, 0, 1, 1);
    cx.fillStyle = '#000';
    cx.fillStyle = c;
    cx.fillRect(0, 0, 1, 1);
    var d = cx.getImageData(0, 0, 1, 1).data;
    return d[3] >= 250 ? [d[0], d[1], d[2]] : null;
  }

  function bg(el) {
    while (el && el.nodeType === 1) {
      var c = rgb(getComputedStyle(el).backgroundColor);
      if (c) return c;
      el = el.parentElement;
    }
    return null;
  }

  function meta() {
    var ms = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i].getAttribute('media');
      if (!m || matchMedia(m).matches) {
        var c = rgb(ms[i].content);
        if (c) return c;
      }
    }
    return null;
  }

  function hx(c) {
    return '#' + c.map(function (v) { return ('0' + v.toString(16)).slice(-2); }).join('');
  }

  // Most common background color along a horizontal line at height y.
  function at(y) {
    var xs = [0.03, 0.12, 0.25, 0.4, 0.6, 0.75, 0.88, 0.97];
    var cnt = {}, best = null, bn = 0;
    for (var i = 0; i < xs.length; i++) {
      var c = bg(document.elementFromPoint(innerWidth * xs[i], y));
      if (!c) continue;
      var k = hx(c);
      cnt[k] = (cnt[k] || 0) + 1;
      if (cnt[k] > bn) { bn = cnt[k]; best = c; }
    }
    return best || meta() || [255, 255, 255];
  }

  var last = '';
  function tick() {
    try {
      var t = hx(at(1)), b = hx(at(innerHeight - 2));
      var s = t + b;
      if (s !== last) { last = s; W2A.colors(t, b); }
    } catch (e) {}
  }

  var tm;
  function sched() { clearTimeout(tm); tm = setTimeout(tick, 150); }

  setInterval(tick, 1000);
  var mo = new MutationObserver(sched);
  mo.observe(document.documentElement, { attributes: true });
  if (document.head) mo.observe(document.head, { childList: true, subtree: true, attributes: true });
  if (document.body) mo.observe(document.body, { attributes: true });
  try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', sched); } catch (e) {}
  addEventListener('resize', sched);
  tick();
})();