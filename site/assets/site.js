/* 零依赖、可在 file:// 下直接打开。 */
(function () {
  'use strict';

  /* ---------- 主题：跟随系统，手动覆盖存在 localStorage ---------- */
  var KEY = 'pb-theme';
  var saved = null;
  try { saved = localStorage.getItem(KEY); } catch (e) { /* 隐私模式里读不到就算了 */ }
  function apply(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    var b = document.getElementById('themeBtn');
    if (b) b.textContent = theme === 'dark' ? '☀ 浅色' : '☾ 深色';
  }
  function systemTheme() {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  apply(saved || systemTheme());
  if (window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function (e) {
      var cur = null;
      try { cur = localStorage.getItem(KEY); } catch (err) { /* ignore */ }
      if (!cur) apply(e.matches ? 'dark' : 'light');
    });
  }
  document.addEventListener('click', function (e) {
    if (e.target && e.target.id === 'themeBtn') {
      var next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      apply(next);
      try { localStorage.setItem(KEY, next); } catch (err) { /* ignore */ }
    }
  });

  /* ---------- 移动端抽屉 ---------- */
  document.addEventListener('click', function (e) {
    if (e.target.closest('#navBtn')) { document.body.classList.toggle('nav-open'); }
    if (e.target.classList && e.target.classList.contains('scrim')) { document.body.classList.remove('nav-open'); }
  });

  /* ---------- 代码块：右上角复制（读 code 的 textContent，不读 innerText，避免复制到高亮 span 的空白） ---------- */
  document.querySelectorAll('pre[data-code]').forEach(function (pre) {
    var src = pre.querySelector('code');
    if (!src) return;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = '复制';
    btn.addEventListener('click', function () {
      var text = src.textContent;
      var done = function () { btn.textContent = '已复制'; setTimeout(function () { btn.textContent = '复制'; }, 1400); };
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(done, function () { legacy(text, done); });
      } else {
        legacy(text, done);
      }
    });
    pre.appendChild(btn);
  });

  function legacy(text, done) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); } catch (e) { /* 复制失败不挡阅读 */ }
    document.body.removeChild(ta);
  }

  /* ---------- 页内目录（h2 起锚点，标题短的用 side nav 第二层） ---------- */
  var toc = document.getElementById('toc');
  if (toc) {
    var heads = document.querySelectorAll('main h2[id]');
    var ul = document.createElement('ul');
    ul.className = 'toc-here';
    heads.forEach(function (h) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '#' + h.id;
      a.textContent = h.textContent.replace(/#$/, '').trim();
      li.appendChild(a);
      ul.appendChild(li);
    });
    toc.appendChild(ul);
  }

  /* ---------- curl 生成器：只改前端预览，不发任何请求 ---------- */  var form = document.getElementById('genForm');
  if (form) {
    var out = document.getElementById('genOut');
    var file = document.getElementById('genFile');
    function render() {
      var f = new FormData(form);
      var payload = {
        channel: String(f.get('channel') || '12345678'),
        secret: String(f.get('secret') || '<频道密钥>'),
        type: String(f.get('type') || 'markdown'),
        level: String(f.get('level') || 'error'),
        sender_id: String(f.get('sender_id') || 'grafana'),
        sender_name: String(f.get('sender_name') || 'Grafana 告警'),
        title: String(f.get('title') || ''),
        content: String(f.get('content') || '')
      };
      if (!payload.title) delete payload.title;
      var url = String(f.get('url') || '').trim();
      if (url) payload.url = url;
      var key = String(f.get('msg_key') || '').trim();
      if (key) payload.msg_key = key;
      var avatar = String(f.get('sender_avatar') || '').trim();
      if (avatar) payload.sender_avatar = avatar;
      var imgs = String(f.get('images') || '').split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
      if (imgs.length) payload.images = imgs;

      var body = JSON.stringify(payload, null, 2);
      if (file) file.textContent = body;
      if (out) {
        out.textContent =
          'curl -sS ' + (String(f.get('base') || 'http://127.0.0.1:8080')) + '/message \\\n' +
          "  -H 'Content-Type: application/json' \\\n" +
          '  --data-binary @payload.json\n\n' +
          '# payload.json 就是上面这份；别用 -d 内联，\n' +
          '# Windows 的 Git Bash 会把单引号里的 JSON 改动，服务端只会回「请求体必须是合法 JSON」';
      }
    }
    form.addEventListener('input', render);
    form.addEventListener('change', render);
    render();
  }

  /* ---------- 仓库卡片：地址只写在 repos.json，改仓库不用动 HTML ---------- */
  var grid = document.getElementById('repoGrid');
  if (grid) {
    var hint = document.getElementById('repoHint');
    function host(url) {
      try { return new URL(url, location.href).host; } catch (e) { return url; }
    }
    function card(item) {
      var a = document.createElement('a');
      a.className = 'repo-card' + (item.primary ? ' primary' : '');
      a.href = item.url;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      var top = document.createElement('span');
      top.className = 'top';
      var b = document.createElement('b');
      b.textContent = item.name;
      top.appendChild(b);
      if (item.label) {
        var t = document.createElement('span');
        t.className = 'host';
        t.textContent = item.label;
        top.appendChild(t);
      }
      var p = document.createElement('p');
      p.textContent = item.desc || host(item.url);
      var go = document.createElement('span');
      go.className = 'go';
      go.textContent = host(item.url) + ' ↗';
      a.appendChild(top);
      a.appendChild(p);
      a.appendChild(go);
      return a;
    }
    function unavailable() {
      // file:// 下浏览器禁止 fetch 同目录文件，这不是页面坏了，得把原因说清楚
      if (hint) {
        hint.hidden = false;
        hint.textContent = '仓库卡片读不到 repos.json：浏览器不允许 file:// 页面 fetch 本地文件。'
          + '用静态服务器打开即可显示，例如在 site/ 目录执行 python -m http.server 后访问 http://127.0.0.1:8000/；'
          '也可以直接打开 repos.json 查看地址。';
      }
    }
    if (location.protocol === 'file:') {
      unavailable();
    } else {
      fetch('repos.json', { cache: 'no-store' })
        .then(function (r) {
          if (!r.ok) throw new Error(String(r.status));
          return r.json();
        })
        .then(function (data) {
          var list = (data && data.repositories) || [];
          if (!list.length) { unavailable(); return; }
          list.forEach(function (item) {
            if (item && item.name && item.url) grid.appendChild(card(item));
          });
          if (data.updated && hint) hint.textContent = '共 ' + list.length + ' 个镜像，清单更新于 ' + data.updated + '。';
        })
        .catch(unavailable);
    }
  }
})();
