// A SESSION AS AN AG-UI AGENT, end to end: `node scripts/e2e/agui.mjs [model]` — the daemon in a home of its own,
// a project; AG-UI's own client (@ag-ui/client, which verifies the event stream; AGUI_CLIENT names its module if it
// is not installed here) starts a thread, every interrupt approved by resume; the file and the answer checked.
// `sbt fatJar` first. The model: an entry of New (default okay-agent on the gateway's Qwen3.6)
import { spawn, execSync } from "child_process"; import fs from "fs";
const { HttpAgent } = await import(process.env.AGUI_CLIENT || "@ag-ui/client");
const model = process.argv[2] || "acp:okay-agent@okay:mlx-community/Qwen3.6-35B-A3B-4bit";
const home = "/tmp/okay-remote-agui-home", projects = "/tmp/okay-remote-agui-projects", project = `${projects}/demo`;
for (const d of [home, projects]) fs.rmSync(d, { recursive: true, force: true });
fs.mkdirSync(`${home}/.okay-remote`, { recursive: true }); fs.mkdirSync(project, { recursive: true });
execSync(`cd ${project} && git init -q && echo '# demo' > README.md`);
fs.writeFileSync(`${home}/.okay-remote/roots`, projects);
const jar = new URL("../../dist/okay-remote.jar", import.meta.url).pathname;
const daemon = spawn("java", [`-Duser.home=${home}`, "-jar", jar, "--port", "7795"], { stdio: ["ignore", "ignore", "inherit"] });
let failed = 0;
const check = (what, ok) => { console.log(`${ok ? "✓" : "✗"} ${what}`); if (!ok) failed++; };
try {
  await new Promise(r => setTimeout(r, 2500));
  const token = fs.readFileSync(`${home}/.okay-remote/token`, "utf8").trim();
  const agent = new HttpAgent({ url: `http://127.0.0.1:7795/agui?t=${token}`, threadId: "agui-e2e" });
  agent.messages = [{ id: "u1", role: "user", content: "Create the file notes.txt in the project directory containing " +
    "the word hello. Then run this shell command on its own: python3 -c \"print(6*7)\" and tell me what it printed." }];
  let interrupts = [], said = "", calls = 0, runs = 0, outcome = "";
  const sub = { onEvent: ({ event }) => {
    if (event.type === "TOOL_CALL_START") { calls++; console.log("  call", event.toolCallName); }
    if (event.type === "TEXT_MESSAGE_CONTENT") said += event.delta;
    if (event.type === "RUN_ERROR") console.log("  error:", event.message);
    if (event.type === "RUN_FINISHED") { outcome = event.outcome?.type || "success";
      interrupts = outcome === "interrupt" ? event.outcome.interrupts : [];
      interrupts.forEach(i => console.log("  interrupt:", i.message)); }
  } };
  const forwardedProps = { project, model };
  await agent.runAgent({ runId: `r${runs++}`, forwardedProps }, sub);
  while (interrupts.length && runs < 12) {
    const resume = interrupts.map(i => ({ interruptId: i.id, status: "resolved", payload: { approved: true } }));
    await agent.runAgent({ runId: `r${runs++}`, resume, forwardedProps }, sub);
  }
  check(`the run ended (${outcome}, ${runs} runs, ${calls} calls)`, outcome === "success");
  check("an interrupt asked and resumed", runs > 1);
  check("the file written", fs.existsSync(`${project}/notes.txt`) && fs.readFileSync(`${project}/notes.txt`, "utf8").includes("hello"));
  check(`the answer (${said.trim().slice(0, 120)})`, said.includes("42"));
} catch (e) { console.log("✗", e); failed++; }
daemon.kill();
process.exit(failed ? 1 : 0);
