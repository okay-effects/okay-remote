// OKAY-REMOTE END TO END, on a phone's screen: the daemon (the jar) in a home of its own, a project, the page in
// headless Chrome; a task started in the page (Claude Haiku), a call the policy asks about approved in the page, the
// agent's answer shown. A real model: it needs Claude Code logged in. Screenshots in /tmp/or-*.png. `sbt fatJar` first.
const { spawn, execSync } = require("child_process"); const fs = require("fs");
const home = "/tmp/okay-remote-e2e-home", projects = "/tmp/okay-remote-e2e-projects";
for (const d of [home, projects]) fs.rmSync(d, { recursive: true, force: true });
fs.mkdirSync(`${home}/.okay-remote`, { recursive: true }); fs.mkdirSync(`${projects}/demo`, { recursive: true });
execSync(`cd ${projects}/demo && git init -q && echo '# demo' > README.md`);
fs.writeFileSync(`${home}/.okay-remote/roots`, projects);
const jar = `${__dirname}/../../dist/okay-remote.jar`;
const daemon = spawn("java", [`-Duser.home=${home}`, "-jar", jar, "--port", "7791"],
  { stdio: ["ignore", "ignore", "inherit"] });
let failed = 0;
const check = (what, ok) => { console.log(`${ok ? "✓" : "✗"} ${what}`); if (!ok) failed++; };
(async () => {
  const b = await require("./cdp")();
  const done = code => { b.close(); daemon.kill(); process.exit(code); };
  try {
    await b.sleep(2000);
    const token = fs.readFileSync(`${home}/.okay-remote/token`, "utf8").trim();
    await b.go(`http://127.0.0.1:7791/?t=${token}`);
    check("connected", (await b.text()).includes("connected"));
    check("the token taken out of the address", !(await b.js("location.search")).includes("t="));
    await b.click("New"); await b.sleep(400);
    const task = 'Run the command: python3 -c "print(6*7)" and tell me what it printed. Nothing else.';
    await b.js(`(()=>{const m=[...document.querySelectorAll('select')][1];
      m.value=[...m.options].find(o=>o.text==='Claude Haiku').value;
      m.dispatchEvent(new Event('change',{bubbles:true}));
      const t=document.querySelector('textarea'); t.value=${JSON.stringify(task)};
      t.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
    await b.shot("/tmp/or-new.png");
    check("started", await b.click("Start"));
    let waiting = false;
    for (let i = 0; i < 60 && !waiting; i++) {
      await b.sleep(1000); waiting = (await b.text()).includes("waiting for you");
    }
    check("a call waits for you", waiting);
    await b.shot("/tmp/or-waiting.png");
    check("approved in the page", await b.click("Approve"));
    let ended = false;
    for (let i = 0; i < 60 && !ended; i++) { await b.sleep(1000); ended = (await b.text()).includes("Done ·"); }
    const text = await b.text();
    check("the turn ended", ended);
    check("the result shown (42)", text.includes("42"));
    check("approved by you", text.includes("approved by you"));
    await b.shot("/tmp/or-done.png");
    await b.click("Sessions"); await b.sleep(500);
    check("the session listed", (await b.text()).includes("demo ·"));
    await b.shot("/tmp/or-sessions.png");
  } catch (e) { console.log("✗", e); failed++; }
  done(failed ? 1 : 0);
})();
