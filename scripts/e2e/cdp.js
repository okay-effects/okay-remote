// headless Chrome over the DevTools protocol, at a phone's size: a page shown, read, clicked, photographed
const { spawn } = require("child_process"); const fs = require("fs");
const sleep = ms => new Promise(r => setTimeout(r, ms));
module.exports = async (port = 9341) => {
  const chrome = spawn("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--headless=new",
    `--remote-debugging-port=${port}`, `--user-data-dir=/tmp/okay-remote-e2e-profile-${port}`, "about:blank"],
    { stdio: "ignore" });
  let target; for (let i = 0; i < 40 && !target; i++) { await sleep(250);
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find(t => t.type === "page"); }
    catch (e) {} }
  const ws = new WebSocket(target.webSocketDebuggerUrl); let n = 0; const pending = {};
  ws.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.id && pending[m.id]) { pending[m.id](m.result); delete pending[m.id]; }
  };
  await new Promise(r => ws.onopen = r);
  const cdp = (method, params = {}) => new Promise(r => {
    const id = ++n; pending[id] = r; ws.send(JSON.stringify({ id, method, params }));
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  await cdp("Emulation.setDeviceMetricsOverride",
    { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  const js = async ex =>
    (await cdp("Runtime.evaluate", { expression: ex, returnByValue: true, awaitPromise: true })).result.value;
  return {
    go: async url => { await cdp("Page.navigate", { url }); await sleep(2500); },
    js,
    shot: async f =>
      fs.writeFileSync(f, Buffer.from((await cdp("Page.captureScreenshot", { format: "png" })).data, "base64")),
    // a button by its text, clicked
    click: async text => js(`(()=>{const b=[...document.querySelectorAll('button,a')]
      .find(b=>b.textContent.trim()===${JSON.stringify(text)}); b && b.click(); return !!b})()`),
    text: () => js("document.body.innerText"),
    close: () => { ws.close(); chrome.kill(); },
    sleep,
  };
};
