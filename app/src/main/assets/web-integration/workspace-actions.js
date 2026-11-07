(function () {
  // 只在顶层同源页面启用；外部页、iframe 与普通浏览器不生效。
  if (window !== window.top || !window.DshaWorkspace || window.__dshaWorkspaceInstalled) return;
  window.__dshaWorkspaceInstalled = true;

  const pending = new Map();
  const api = window.DSHA || (window.DSHA = {});

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

  function overlay() {
    const mask = document.createElement('div');
    mask.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:2147483000;display:flex;align-items:center;justify-content:center;padding:24px;';
    const box = document.createElement('div');
    box.style.cssText = 'background:var(--dsha-bg,#fff);color:var(--dsha-fg,#111);border-radius:14px;padding:18px 16px;max-width:320px;width:100%;box-shadow:0 12px 40px rgba(0,0,0,.3);font:15px/1.5 system-ui,sans-serif;';
    mask.appendChild(box);
    return {mask, box};
  }

  function confirmDelete(name, path) {
    return new Promise(resolve => {
      const {mask, box} = overlay();
      const title = document.createElement('div');
      title.textContent = '删除';
      title.style.cssText = 'font-weight:600;font-size:17px;margin-bottom:10px;';
      const text = document.createElement('div');
      text.textContent = '确定删除「' + name + '」？此操作不可恢复。';
      text.style.cssText = 'opacity:.85;margin-bottom:16px;word-break:break-all;';
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;gap:10px;justify-content:flex-end;';
      const cancel = document.createElement('button');
      cancel.textContent = '取消';
      cancel.style.cssText = 'padding:9px 16px;border-radius:9px;border:1px solid rgba(128,128,128,.4);background:transparent;color:inherit;font-size:15px;';
      const ok = document.createElement('button');
      ok.textContent = '删除';
      ok.style.cssText = 'padding:9px 16px;border-radius:9px;border:none;background:#d93025;color:#fff;font-size:15px;font-weight:600;';
      cancel.onclick = () => { mask.remove(); resolve(false); };
      ok.onclick = () => { mask.remove(); resolve(true); };
      mask.onclick = event => { if (event.target === mask) { mask.remove(); resolve(false); } };
      row.append(cancel, ok);
      box.append(title, text, row);
      document.body.appendChild(mask);
    });
  }

  function toast(message) {
    const node = document.createElement('div');
    node.textContent = message;
    node.style.cssText = 'position:fixed;left:50%;bottom:72px;transform:translateX(-50%);background:rgba(32,32,32,.92);color:#fff;padding:10px 16px;border-radius:10px;font:14px system-ui,sans-serif;z-index:2147483001;max-width:80vw;';
    document.body.appendChild(node);
    setTimeout(() => node.remove(), 2600);
  }

  function decorate(item) {
    if (item.dataset.dshaActions) return;
    const path = item.getAttribute('data-files-path');
    if (!path) return;
    item.dataset.dshaActions = '1';
    item.style.position = 'relative';

    const wrap = document.createElement('span');
    wrap.style.cssText = 'position:absolute;right:2px;top:50%;transform:translateY(-50%);display:flex;gap:2px;';

    const nameNode = item.querySelector('[class*="name"]');
    const name = nameNode ? nameNode.textContent : path.split('/').filter(Boolean).pop();

    const isApk = /\.apk$/i.test(name || '');
    if (isApk) {
      const install = document.createElement('button');
      install.type = 'button';
      install.textContent = '安装';
      install.style.cssText = 'border:none;background:#2f6feb;color:#fff;border-radius:7px;padding:3px 8px;font-size:12px;line-height:1.4;';
      install.onclick = event => {
        event.preventDefault();
        event.stopPropagation();
        toast('准备安装 ' + name + '…');
        api.installPath(path).catch(error => toast('无法安装：' + error.message));
      };
      wrap.appendChild(install);
    }

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '删';
    remove.title = '删除';
    remove.style.cssText = 'border:none;background:rgba(217,48,37,.12);color:#d93025;border-radius:7px;padding:3px 7px;font-size:12px;line-height:1.4;';
    remove.onclick = event => {
      event.preventDefault();
      event.stopPropagation();
      confirmDelete(name, path).then(ok => {
        if (!ok) return;
        api.deletePath(path)
          .then(() => { item.remove(); toast('已删除 ' + name); })
          .catch(error => toast('删除失败：' + error.message));
      });
    };
    wrap.appendChild(remove);
    item.appendChild(wrap);
  }

  function scan() {
    document.querySelectorAll('[data-files-entry][data-files-path]').forEach(decorate);
  }

  scan();
  new MutationObserver(scan).observe(document.documentElement, {childList: true, subtree: true});
})();
