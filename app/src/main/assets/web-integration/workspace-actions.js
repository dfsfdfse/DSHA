(function () {
  // 只在顶层同源页面启用；外部页、iframe 与普通浏览器不生效。
  if (window !== window.top || !window.DshaWorkspace || window.__dshaWorkspaceInstalled) return;
  window.__dshaWorkspaceInstalled = true;

  const pending = new Map();
  const api = window.DSHA || (window.DSHA = {});

  const TRASH = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12"/><path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>';
  const INSTALL = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v11"/><path d="M7.5 10.5 12 15l4.5-4.5"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>';

  function request(action, path) {
    const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (pending.delete(id)) reject(new Error('操作超时'));
      }, 180000);
      pending.set(id, {resolve, reject, timer});
      try {
        DshaWorkspace.postMessage(JSON.stringify({id, action, path}));
      } catch (error) {
        clearTimeout(timer);
        pending.delete(id);
        reject(error);
      }
    });
  }

  DshaWorkspace.onmessage = event => {
    let result;
    try { result = JSON.parse(event.data); } catch (_) { return; }
    const task = pending.get(result.id);
    if (!task) return;
    pending.delete(result.id);
    clearTimeout(task.timer);
    if (result.ok) task.resolve();
    else task.reject(new Error(result.detail || '操作失败'));
  };

  api.deletePath = path => request('delete', path);
  api.installPath = path => request('install', path);

  /** 解析页面实际使用的主题色，弹层跟随亮/暗主题。 */
  function themeColors() {
    let bg = '', fg = '';
    for (const el of [document.body, document.documentElement]) {
      if (!el) continue;
      const cs = getComputedStyle(el);
      if (!bg && cs.backgroundColor && cs.backgroundColor !== 'rgba(0, 0, 0, 0)') bg = cs.backgroundColor;
      if (!fg && cs.color) fg = cs.color;
    }
    if (!bg || bg === 'rgba(0, 0, 0, 0)') bg = matchMedia('(prefers-color-scheme: dark)').matches ? '#1f1f1f' : '#ffffff';
    if (!fg) fg = matchMedia('(prefers-color-scheme: dark)').matches ? '#f2f2f2' : '#111111';
    const probe = document.createElement('span');
    probe.style.color = bg;
    document.body.appendChild(probe);
    const rgb = getComputedStyle(probe).color.match(/\d+/g);
    probe.remove();
    const dark = rgb ? (Number(rgb[0]) * 299 + Number(rgb[1]) * 587 + Number(rgb[2]) * 114) / 1000 < 128 : false;
    return {bg, fg, dark};
  }

  // 贴点击位置的小确认面板：跟随主题，不铺满屏幕。
  function confirmPanel(event, name) {
    return new Promise(resolve => {
      const theme = themeColors();
      const panel = document.createElement('div');
      panel.style.cssText = [
        'position:fixed', 'z-index:2147483600',
        'background:' + theme.bg, 'color:' + theme.fg,
        'border:1px solid ' + (theme.dark ? 'rgba(255,255,255,.14)' : 'rgba(0,0,0,.10)'),
        'border-radius:14px', 'box-shadow:0 10px 32px rgba(0,0,0,' + (theme.dark ? '.55' : '.22') + ')',
        'padding:14px', 'width:236px', 'max-width:82vw',
        'font:14px/1.5 system-ui,-apple-system,sans-serif',
      ].join(';');

      const text = document.createElement('div');
      text.textContent = '删除「' + name + '」？';
      text.style.cssText = 'margin-bottom:4px;word-break:break-all;font-weight:500;';
      const hint = document.createElement('div');
      hint.textContent = '删除后不可恢复';
      hint.style.cssText = 'margin-bottom:12px;font-size:12px;opacity:.55;';

      const row = document.createElement('div');
      row.style.cssText = 'display:flex;gap:8px;justify-content:flex-end;';

      const cancel = document.createElement('button');
      cancel.textContent = '取消';
      cancel.style.cssText = 'padding:7px 16px;border-radius:9px;border:1px solid ' + (theme.dark ? 'rgba(255,255,255,.18)' : 'rgba(0,0,0,.14)') + ';background:transparent;color:inherit;font-size:14px;';
      const ok = document.createElement('button');
      ok.textContent = '删除';
      ok.style.cssText = 'padding:7px 16px;border-radius:9px;border:none;background:#d93025;color:#fff;font-size:14px;font-weight:600;';

      const close = answer => {
        panel.remove();
        document.removeEventListener('pointerdown', outside, true);
        resolve(answer);
      };
      const outside = e => { if (!panel.contains(e.target)) close(false); };

      cancel.onclick = e => { e.stopPropagation(); close(false); };
      ok.onclick = e => { e.stopPropagation(); close(true); };
      panel.addEventListener('pointerdown', e => e.stopPropagation());
      panel.addEventListener('click', e => e.stopPropagation());
      row.append(cancel, ok);
      panel.append(text, hint, row);
      document.body.appendChild(panel);

      const rect = panel.getBoundingClientRect();
      const vw = window.innerWidth, vh = window.innerHeight;
      let left = (event.clientX || vw / 2) - rect.width + 24;
      let top = (event.clientY || vh / 2) + 10;
      left = Math.max(8, Math.min(left, vw - rect.width - 8));
      if (top + rect.height > vh - 8) top = Math.max(8, (event.clientY || vh / 2) - rect.height - 10);
      panel.style.left = left + 'px';
      panel.style.top = top + 'px';

      setTimeout(() => document.addEventListener('pointerdown', outside, true), 0);
    });
  }

  function toast(message) {
    const theme = themeColors();
    const node = document.createElement('div');
    node.textContent = message;
    node.style.cssText = 'position:fixed;left:50%;bottom:72px;transform:translateX(-50%);background:' + (theme.dark ? 'rgba(60,60,60,.96)' : 'rgba(32,32,32,.92)') + ';color:#fff;padding:10px 16px;border-radius:10px;font:14px system-ui,sans-serif;z-index:2147483601;max-width:80vw;text-align:center;';
    document.body.appendChild(node);
    setTimeout(() => node.remove(), 2600);
  }

  /** 触发文件树的重新读取；找不到刷新控件就等目录监听自然刷新。 */
  function refreshTree() {
    const candidates = document.querySelectorAll('button[aria-label],button[title]');
    for (const button of candidates) {
      const label = (button.getAttribute('aria-label') || button.getAttribute('title') || '');
      if (/刷新|重新读取|reload|refresh/i.test(label)) {
        button.click();
        return true;
      }
    }
    return false;
  }

  function iconButton(svg, color, label, handler) {
    const button = document.createElement('span');
    button.setAttribute('role', 'button');
    button.setAttribute('aria-label', label);
    button.title = label;
    button.innerHTML = svg;
    button.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;border-radius:8px;color:' + color + ';background:transparent;flex:0 0 auto;cursor:pointer;';
    button.addEventListener('pointerdown', event => event.stopPropagation());
    button.addEventListener('click', event => {
      event.preventDefault();
      event.stopPropagation();
      handler(event);
    });
    return button;
  }

  function decorate(item) {
    const row = item.querySelector(':scope > button');
    const path = item.getAttribute('data-files-path');
    if (!row || !path) return;
    if (row.dataset.dshaActions === '1') return;
    row.dataset.dshaActions = '1';

    const nameNode = row.querySelector('[class*="name"]');
    const name = nameNode ? nameNode.textContent : path.split('/').filter(Boolean).pop();
    const isApk = /\.apk$/i.test(name || '');

    const actions = document.createElement('span');
    actions.style.cssText = 'margin-left:auto;display:inline-flex;align-items:center;gap:2px;flex:0 0 auto;';

    if (isApk) {
      actions.appendChild(iconButton(INSTALL, '#2f6feb', '安装', () => {
        toast('正在调起安装：' + name);
        api.installPath(path)
          .then(() => toast('已交给系统安装器'))
          .catch(error => toast('无法安装：' + error.message));
      }));
    }
    actions.appendChild(iconButton(TRASH, '#d93025', '删除', event => {
      confirmPanel(event, name).then(ok => {
        if (!ok) return;
        api.deletePath(path)
          .then(() => {
            // 不直接删 React 节点：先隐藏，再让目录监听/刷新重建整层。
            item.style.display = 'none';
            if (!refreshTree()) setTimeout(() => { item.style.display = ''; }, 1200);
            toast('已删除 ' + name);
          })
          .catch(error => toast('删除失败：' + error.message));
      });
    }));

    if (nameNode) nameNode.style.minWidth = '0';
    row.style.paddingRight = '4px';
    row.appendChild(actions);
  }

  function scan() {
    document.querySelectorAll('[data-files-entry][data-files-path]').forEach(decorate);
  }

  scan();
  new MutationObserver(scan).observe(document.documentElement, {childList: true, subtree: true});
})();
