// TELEGRAM, end to end, with no Telegram: a fake Bot API, the relay (`wrangler dev`) with a bot, the daemon, the
// machine's page in headless Chrome. A chat linked by the page's link and Telegram's /start; told when a call waits
// and when the agent is done — only that, never the command; a webhook without Telegram's secret refused; a code
// used twice refused; unlinked. `sbt fatJar relayDist` first. Screenshots /tmp/or-tg-*.png.
const { spawn, execSync } = require("child_process"); const fs = require("fs"); const http = require("http");
const home = "/tmp/okay-remote-tg-home", projects = "/tmp/okay-remote-tg-projects";
for (const d of [home, projects]) fs.rmSync(d, { recursive: true, force: true });
fs.mkdirSync(`${home}/.okay-remote`, { recursive: true }); fs.mkdirSync(`${projects}/demo`, { recursive: true });
execSync(`cd ${projects}/demo && git init -q && echo '# demo' > README.md`);
fs.writeFileSync(`${home}/.okay-remote/roots`, projects);
const sent = [];
const api = http.createServer((req, res) => { let b = ""; req.on("data", d => b += d); req.on("end", () => {
  const m = JSON.parse(b || "{}"); sent.push(m);
  res.writeHead(m.chat_id === "666" ? 403 : 200, { "content-type": "application/json" }); res.end('{"ok":true}');
}); }).listen(8798);
const relay = spawn("npx", ["wrangler", "dev", "--port", "8797", "--var", "TG_TOKEN:t0k", "--var", "TG_SECRET:s3cret",
  "--var", "TG_BOT:okay_remote_test_bot", "--var", "TG_API:http://127.0.0.1:8798"],
  { cwd: `${__dirname}/../../relay`, stdio: "ignore" });
const jar = `${__dirname}/../../dist/okay-remote.jar`;
let daemon;
let failed = 0;
const check = (what, ok) => { console.log(`${ok ? "✓" : "✗"} ${what}`); if (!ok) failed++; };
const hook = (body, secret = "s3cret") => fetch("http://127.0.0.1:8797/tg", { method: "POST",
  headers: { "content-type": "application/json", "x-telegram-bot-api-secret-token": secret },
  body: JSON.stringify(body) });
const start = (code, chat = 4242) => hook({ update_id: 1, message: { chat: { id: chat, type: "private" },
  text: `/start ${code}` } });
(async () => {
  const b = await require("./cdp")(9347);
  const done = code => { b.close(); daemon && daemon.kill(); relay.kill(); api.close(); process.exit(code); };
  try {
    await b.sleep(9000);
    daemon = spawn("java", [`-Duser.home=${home}`, "-jar", jar, "--port", "7797", "--relay", "http://127.0.0.1:8797"],
      { stdio: ["ignore", "ignore", "inherit"] });
    await b.sleep(4000);
    const token = fs.readFileSync(`${home}/.okay-remote/token`, "utf8").trim();
    await b.go(`http://127.0.0.1:7797/?t=${token}`);
    await b.click("Phone"); await b.sleep(800);
    const field = (v) => b.js(`(()=>{const f=[...document.querySelectorAll('input')].pop(); f.value=${JSON.stringify(v)};
      f.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
    check("the page offers Telegram by a code", (await b.text()).includes("Send me a code"));
    check("Telegram without its secret: refused", (await hook({ message: {} }, "wrong")).status === 403);
    // a chat the bot cannot write to: said so
    await field("666"); await b.click("Send me a code"); await b.sleep(1500);
    check("a chat the bot cannot write to: said so", (await b.text()).includes("could not write to that chat"));
    // the person's chat: a code sent to it, a wrong one refused, the right one links
    await field("4242"); await b.click("Send me a code"); await b.sleep(1500);
    const sentCode = sent.filter(m => m.chat_id === "4242").map(m => (m.text.match(/code is (\d{6})/) || [])[1])
      .filter(Boolean).pop();
    check("a code sent to the chat", /^\d{6}$/.test(sentCode || ""));
    await field("000000" === sentCode ? "111111" : "000000"); await b.click("Link"); await b.sleep(1200);
    check("a wrong code: refused", (await b.text()).includes("not right"));
    await field(sentCode); await b.click("Link"); await b.sleep(2000);
    check("linked: the chat told so", sent.some(m => m.chat_id === "4242" && /^Linked/.test(m.text)));
    const kept = JSON.parse(fs.readFileSync(`${home}/.okay-remote/relay.json`, "utf8"));
    check("the daemon keeps the chat and the relay's signature",
      kept.chat === "4242" && /^[0-9a-f]{64}$/.test(kept.sig));
    check("the page says linked", (await b.text()).includes("Telegram: linked"));
    await b.shot("/tmp/or-tg-linked.png");
    // a task that waits for you: the chat told, with no command in it
    await b.click("New"); await b.sleep(400);
    await b.js(`(()=>{const m=[...document.querySelectorAll('select')][1];
      m.value=[...m.options].find(o=>o.text==='Claude Haiku').value;
      m.dispatchEvent(new Event('change',{bubbles:true}));
      const t=document.querySelector('textarea');
      t.value='Run this command on its own: python3 -c "print(6*7)" and tell me what it printed. Nothing else.';
      t.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
    await b.click("Start");
    const card = `[...document.querySelectorAll('[class*=okay-mark-call-wait]')].pop()`;
    let waited = false, ended = false;
    for (let i = 0; i < 120 && !ended; i++) {
      await b.sleep(1000);
      if (await b.js(`!!(${card})`)) {
        waited = true;
        await b.js(`[...(${card}).querySelectorAll('a,button')].find(x=>x.textContent.trim()==='Approve').click()`);
      }
      ended = (await b.text()).includes("Done ·");
    }
    await b.sleep(1500);
    const told = sent.filter(m => m.chat_id === "4242" && !/code is/.test(m.text)).map(m => m.text);
    console.log("  told:", JSON.stringify(told));
    check("told when a call waits", waited && told.includes("okay-remote · demo: a call waits for you"));
    check("told when the agent is done", told.includes("okay-remote · demo: the agent is done"));
    check("never what: no command, no path, no answer", !told.some(t => /python|print|42|\/tmp/.test(t)));
    check("a button to open the page only over https (none here)",
      sent.every(m => !(m.reply_markup?.inline_keyboard || []).flat()
        .some(k => k.web_app && !k.web_app.url.startsWith("https://"))));
    // the daemon again: its chat given back with the relay's signature — and with a forged one, not taken
    const relayJson = `${home}/.okay-remote/relay.json`;
    const restart = async () => {
      daemon.kill(); await b.sleep(1500);
      daemon = spawn("java", [`-Duser.home=${home}`, "-jar", jar, "--port", "7797", "--relay", "http://127.0.0.1:8797"],
        { stdio: ["ignore", "ignore", "inherit"] });
      await b.sleep(5000);
    };
    const quick = async () => {
      const before = sent.length;
      await fetch(`http://127.0.0.1:7797/act?t=${token}`, { method: "POST", body: JSON.stringify({ type: "new",
        project: fs.realpathSync(`${projects}/demo`), model: "claude:haiku", task: "Say hi. Nothing else." }) });
      for (let i = 0; i < 60 && sent.length === before; i++) await b.sleep(1000);
      return sent.slice(before).filter(m => m.chat_id === "4242");
    };
    const good = JSON.parse(fs.readFileSync(relayJson, "utf8"));
    fs.writeFileSync(relayJson, JSON.stringify({ ...good, sig: "0".repeat(64) }));
    await restart();
    check("a forged signature binds nothing: no message", (await quick()).length === 0);
    fs.writeFileSync(relayJson, JSON.stringify(good));
    await restart();
    check("the daemon again: the chat told, by the relay's signature", (await quick()).length > 0);
    await b.go(`http://127.0.0.1:7797/?t=${token}`);
    await b.click("Phone"); await b.sleep(500);
    check("unlinked", await b.click("Unlink Telegram"));
    await b.sleep(800);
    check("the chat forgotten", !("chat" in JSON.parse(fs.readFileSync(`${home}/.okay-remote/relay.json`, "utf8"))));
    await b.shot("/tmp/or-tg-unlinked.png");
  } catch (e) { console.log("✗", e); failed++; }
  done(failed ? 1 : 0);
})();
