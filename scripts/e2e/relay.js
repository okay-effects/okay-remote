// OKAY-REMOTE THROUGH THE RELAY, end to end, on a phone's screen: the relay (`npx wrangler dev --port 8799` in
// relay/, after `sbt relayDist`), the daemon dialling it, the page opened from the relay by the pairing link (its
// secret in the fragment); a task (Claude Haiku), a call always allowed (the rule in the policy), the changes, the
// answer; a stranger's frames ignored; the phone unpaired. RELAY=<url> for another relay. Screenshots /tmp/or-relay-*.
const { spawn, execSync } = require("child_process"); const fs = require("fs");
const relay = process.env.RELAY || "http://127.0.0.1:8799";
const home = "/tmp/okay-remote-relay-home", projects = "/tmp/okay-remote-relay-projects";
for (const d of [home, projects]) fs.rmSync(d, { recursive: true, force: true });
fs.mkdirSync(`${home}/.okay-remote`, { recursive: true }); fs.mkdirSync(`${projects}/demo`, { recursive: true });
execSync(`cd ${projects}/demo && git init -q && echo '# demo' > README.md && git add -A && ` +
  `git -c user.email=e2e@x -c user.name=e2e commit -qm base`);
fs.writeFileSync(`${home}/.okay-remote/roots`, projects);
const jar = `${__dirname}/../../dist/okay-remote.jar`;
const daemon = spawn("java", [`-Duser.home=${home}`, "-jar", jar, "--port", "7792", "--relay", relay],
  { stdio: ["ignore", "ignore", "inherit"] });
let failed = 0;
const check = (what, ok) => { console.log(`${ok ? "✓" : "✗"} ${what}`); if (!ok) failed++; };
const until = async (b, f, s = 60) => { for (let i = 0; i < s; i++) { if (await f()) return true; await b.sleep(1000); }
  return false; };
(async () => {
  const b = await require("./cdp")(9345);
  const done = code => { b.close(); daemon.kill(); process.exit(code); };
  try {
    await b.sleep(3000);
    const kept = JSON.parse(fs.readFileSync(`${home}/.okay-remote/relay.json`, "utf8"));
    check("the secret kept readable by its owner alone",
      (fs.statSync(`${home}/.okay-remote/relay.json`).mode & 0o077) === 0);
    await b.go(`${relay}/#s=${kept.secret}`);
    check("connected through the relay", await until(b, async () => (await b.text()).includes("connected"), 20));
    check("the secret taken out of the address", (await b.js("location.hash")) === "");
    // a stranger with the room's auth but not the key: its frames go nowhere
    const crypto = require("crypto");
    const S = Buffer.from(kept.secret, "base64url");
    const auth = crypto.createHash("sha256").update(Buffer.concat([Buffer.from("okay-remote/auth:"), S])).digest("hex");
    const room = crypto.createHash("sha256").update(auth).digest("hex");
    const ws = new WebSocket(`${relay.replace(/^http/, "ws")}/r/${room}/phone?auth=${auth}`);
    let strangerGot = [];
    ws.onmessage = e => strangerGot.push(String(e.data));
    await new Promise(r => ws.onopen = r);
    ws.send(Buffer.from("not sealed by the key").toString("base64url"));
    ws.send(JSON.stringify({ kind: "act", body: { type: "forget" } }));
    await b.click("New"); await b.sleep(500);
    const task = "Create notes.txt with the line hello (the Write tool), then run exactly this command, alone: " +
      "python3 -c \"print(6*7)\" — and tell me its output. Run every command on its own, never chained. " +
      "Nothing else.";
    await b.js(`(()=>{const m=[...document.querySelectorAll('select')][1];
      m.value=[...m.options].find(o=>o.text==='Claude Haiku').value;
      m.dispatchEvent(new Event('change',{bubbles:true}));
      const t=document.querySelector('textarea'); t.value=${JSON.stringify(task)};
      t.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
    check("started", await b.click("Start"));
    // each call that waits: python3 always allowed (the rule goes into the policy), anything else approved once
    const card = `[...document.querySelectorAll('[class*=okay-mark-call-wait]')].pop()`;
    let always = false, waited = false;
    for (let i = 0; i < 90 && !always; i++) {
      const gist = await b.js(`(${card})?.innerText || ""`);
      if (gist.includes("waiting for you")) {
        waited = true;
        const alone = gist.includes('python3 -c "print(6*7)"') && !/&&|;|\|/.test(gist);
        const label = alone ? "Always allow" : "Approve";
        if (alone) await b.shot("/tmp/or-relay-waiting.png");
        await b.js(`[...(${card}).querySelectorAll('a,button')].find(x=>x.textContent.trim()==='${label}').click()`);
        await b.sleep(1500);
        always = alone && fs.existsSync(`${home}/.okay-remote/policy.policy`);
      } else await b.sleep(1000);
    }
    check("a call waited for you", waited);
    check("always allowed", always);
    const policy = `${home}/.okay-remote/policy.policy`;
    check("the rule in the policy", await until(b, async () => fs.existsSync(policy) &&
      fs.readFileSync(policy, "utf8").includes('allow Bash when command = "python3'), 10));
    check("the turn ended", await until(b, async () => (await b.text()).includes("Done ·"), 90));
    check("the answer (42)", (await b.text()).includes("42"));
    await b.shot("/tmp/or-relay-done.png");
    await b.click("Changes");
    check("the changes: notes.txt", await until(b, async () => (await b.text()).includes("notes.txt"), 15));
    await b.shot("/tmp/or-relay-changes.png");
    const readable = m => { try { JSON.parse(m); return true; } catch (e) { return /[ {}":]/.test(m); } };
    check("the stranger read nothing: the daemon's presence, and sealed frames",
      strangerGot.filter(m => m !== "online" && m !== "offline").every(m => !readable(m)));
    check("the session not forgotten by the stranger", fs.readdirSync(`${home}/.okay-remote/sessions`).length === 2);
    const verify = execSync(`java -Duser.home=${home} -jar ${jar} verify`).toString();
    check("the record's chain unbroken", verify.includes("the chain unbroken"));
    ws.close();
    await b.click("Phone"); await b.sleep(500);
    await b.shot("/tmp/or-relay-phone.png");
    check("unpaired", await b.click("Unpair this phone"));
    await b.go(`${relay}/`);
    check("a page with no pairing says how to pair", (await b.text()).includes("Pair this phone"));
    await b.shot("/tmp/or-relay-unpaired.png");
  } catch (e) { console.log("✗", e); failed++; }
  done(failed ? 1 : 0);
})();
