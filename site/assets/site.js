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

  /* ---------- 代码高亮：一张规则表，不引第三方、不改变 textContent ---------- */
  /* 每条规则 = [class, 正则]，正则里只准用 (?:)。顺序就是优先级：注释 > 字符串 > 标志 > 变量 > 关键字 > 数字。
     只有 SQL 用忽略大小写：全语言忽略会把 mvn/deploy/port 这类普通词当成环境变量名染色。
     高亮只做一件事——把 textContent 拆成若干 <span>，拼回去还是原来那一串，所以复制按钮复制到的仍是干净命令。 */
  var SHELL_KW = 'if|then|else|elif|fi|for|in|do|done|while|until|case|esac|function|return|break|continue|'
    + 'export|source|set|cd|echo|printf|cp|mv|rm|mkdir|chmod|chown|ln|git|curl|wget|npm|npx|node|java|javac|mvn|'
    + 'docker|compose|bash|adb|dotnet|gradlew|openssl|grep|sed|awk|sort|head|tail|cat|less|mysql|mysqldump|'
    + 'certutil|tar|gzip|xz|sudo|systemctl|service|age|env';
  var SQL_KW = 'create|database|table|user|role|grant|revoke|on|to|flush|privileges|select|from|where|and|or|not|'
    + 'null|insert|into|values|update|set|delete|join|left|inner|outer|order|by|group|limit|offset|count|sum|as|is|'
    + 'exists|if|identified|password|alter|drop|index|primary|key|references|desc|show|like|between|having|distinct';
  var QUOTED = "'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"";
  /* 命令行参数要连着点号和取值一起吃掉：--pb.s3.delete-grace=2s 是一条参数，不是四段。 */
  var FLAG = /(?:^|\s)-{1,2}[A-Za-z][\w.-]*(?:=\S*)?/;
  var RULES = {
    bash: { f: 'g', r: [
      ['c', /#[^\n]*/],
      ['s', new RegExp(QUOTED)],
      ['f', FLAG],
      ['n', /\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\b[A-Z][A-Z0-9_]{2,}\b/],
      ['k', new RegExp('\\b(?:' + SHELL_KW + ')\\b')],
      ['n', /\b\d+(?:\.\d+)*\b/]
    ] },
    sql: { f: 'gi', r: [
      ['c', /--[^\n]*|\/\*[\s\S]*?\*\//],
      ['s', /'(?:[^']|'')*'/],
      ['k', new RegExp('\\b(?:' + SQL_KW + ')\\b')],
      ['n', /\b\d+\b/]
    ] },
    json: { f: 'g', r: [
      ['n', /"(?:[^"\\]|\\.)*"(?=\s*:)/],
      ['s', /"(?:[^"\\]|\\.)*"/],
      ['k', /\b(?:true|false|null)\b/],
      ['n', /-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/]
    ] }
  };
  RULES.powershell = RULES.bash;
  RULES.python = RULES.bash;

  function paint(pre) {
    var lang = pre.getAttribute('data-code');
    var rules = RULES[lang];
    var src = pre.querySelector('code');
    if (!rules || !src) return;
    var text = src.textContent;
    var re = new RegExp(rules.r.map(function (r) { return '(' + r[1].source + ')'; }).join('|'), rules.f);
    var frag = document.createDocumentFragment(), last = 0, m, i;
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
      for (i = 1; i < m.length; i++) {
        if (m[i] === undefined) continue;
        var span = document.createElement('span');
        span.className = rules.r[i - 1][0];
        span.textContent = m[i];
        frag.appendChild(span);
        break;
      }
      last = re.lastIndex;
      if (re.lastIndex === m.index) re.lastIndex++;   /* 零宽匹配会把 while 卡死 */
    }
    frag.appendChild(document.createTextNode(text.slice(last)));
    src.textContent = '';
    src.appendChild(frag);
  }
  document.querySelectorAll('pre[data-code]').forEach(paint);

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
          'curl -sS ' + (String(f.get('base') || 'http://127.0.0.1:7404')) + '/message \\\n' +
          "  -H 'Content-Type: application/json' \\\n" +
          '  --data-binary @payload.json\n\n' +
          '# payload.json 就是上面这份；别用 -d 内联，\n' +
          '# Windows 的 Git Bash 会把单引号里的 JSON 改动，服务端只会回「请求体必须是合法 JSON」';
      }
      if (file) paint(file.parentNode);
      if (out) paint(out.parentNode);
    }
    form.addEventListener('input', render);
    form.addEventListener('change', render);
    render();
  }
})();
