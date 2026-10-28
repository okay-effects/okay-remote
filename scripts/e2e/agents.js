// ANY AGENT, end to end, on a phone's screen: `node scripts/e2e/agents.js "Codex"` (the agent's label on New). The
// daemon in a home of its own, a project; a task that writes a file and runs a command; every call that waits
// approved in the page; the answer, the file, and the policy's decisions checked. `sbt fatJar` first.
const { spawn, execSync } = require("child_process"); const fs = require("fs");
const label = process.argv[2] || "Claude Code (ACP)";
const slug = label.toLowerCase().replace(/[^a-z]+/g, "-");
const home = `/tmp/okay-remote-agents-home`, projects = `/tmp/okay-remote-agents-projects`;
for (const d of [home, projects]) fs.rmSync(d, { recursive: true, force: true });
fs.mkdirSync(`${home}/.okay-remote`, { recursive: true }); fs.mkdirSync(`${projects}/demo`, { recursive: true });
execSync(`cd ${projects}/demo && git init -q && echo '# demo' > README.md`);
fs.writeFileSync(`${home}/.okay-remote/roots`, projects);
const jar = `${__dirname}/../../dist/okay-remote.jar`;
const daemon = spawn("java", [`-Duser.home=${home}`, "-jar", jar, "--port", "7794"],
  { stdio: ["ignore", "ignore", "inherit"], env: { ...process.env, HOME: process.env.HOME } });
let failed = 0;
const check = (what, ok) => { console.log(`${ok ? "✓" : "✗"} ${label}: ${what}`); if (!ok) failed++; };
(async () => {
  const b = await require("./cdp")(9346);
  const done = code => { b.close(); daemon.kill(); process.exit(code); };
  try {
    await b.sleep(2500);
    const token = fs.readFileSync(`${home}/.okay-remote/token`, "utf8").trim();
    await b.go(`http://127.0.0.1:7794/?t=${token}`);
    await b.click("New"); await b.sleep(400);
    const task = "Create the file notes.txt containing the word hello. Then run this shell command on its own: " +
      "python3 -c \"print(6*7)\" and tell me what it printed. Do nothing else.";
    const chosen = await b.js(`(()=>{const m=[...document.querySelectorAll('select')][1];
      const o=[...m.options].find(o=>o.text===${JSON.stringify(label)}); if(!o) return false;
      m.value=o.value; m.dispatchEvent(new Event('change',{bubbles:true}));
      const t=document.querySelector('textarea'); t.value=${JSON.stringify(task)};
      t.dispatchEvent(new Event('input',{bubbles:true})); return true})()`);
    check("offered on New", chosen);
    if (!chosen) return done(1);
    await b.click("Start");
    const card = `[...document.querySelectorAll('[class*=okay-mark-call-wait]')].pop()`;
    let ended = false, approved = 0;
    for (let i = 0; i < 150 && !ended; i++) {
      if (i % 30 === 29) await b.shot(`/tmp/or-${slug}-at-${i}.png`);
      await b.sleep(1000);
      const text = await b.text();
      ended = /Done ·|Not done ·|The agent stopped/.test(text);
      if (await b.js(`!!(${card})`)) {
        if (approved === 0) await b.shot(`/tmp/or-${slug}-waiting.png`);
        await b.js(`[...(${card}).querySelectorAll('a,button')].find(x=>x.textContent.trim()==='Approve').click()`);
        approved++;
      }
    }
    const text = await b.text();
    await b.shot(`/tmp/or-${slug}-done.png`);
    check("the turn ended", /Done ·/.test(text));
    check("the answer (42)", text.includes("42"));
    check("the file written", fs.existsSync(`${projects}/demo/notes.txt`) &&
      fs.readFileSync(`${projects}/demo/notes.txt`, "utf8").includes("hello"));
    check(`a call waited for the phone (${approved} approved)`, approved > 0);
    const steps = fs.readdirSync(`${home}/.okay-remote/sessions`).filter(f => f.endsWith(".jsonl"))
      .flatMap(f => fs.readFileSync(`${home}/.okay-remote/sessions/${f}`, "utf8").trim().split("\n"))
      .map(l => JSON.parse(l));
    const decided = steps.filter(s => s.k === "decided").map(s => s.verdict);
    console.log(`  decisions: ${decided.join(", ")}`);
    check("the policy decided its calls", decided.includes("allow") && decided.includes("approved"));
  } catch (e) { console.log("✗", e); failed++; }
  done(failed ? 1 : 0);
})();
