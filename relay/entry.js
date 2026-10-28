// the relay's entry: its logic Scala (okay-serverless's Worker, dist/okay-remote-relay.js); here only what
// JavaScript must say — the entry point, the rooms' Durable Object class, the page's files as text
import { DurableObject } from "cloudflare:workers";
import { worker, setFiles } from "./dist/okay-remote-relay.js";
import files from "./dist/assets.js";
setFiles(files);

export class Room extends DurableObject {
  fetch(request) { return worker.objectFetch("ROOMS", this.ctx, this.env, request); }
  alarm() { return worker.objectAlarm("ROOMS", this.ctx, this.env); }
  webSocketMessage(ws, message) { return worker.objectMessage("ROOMS", this.ctx, this.env, ws, message); }
  webSocketClose(ws) { return worker.objectClose("ROOMS", this.ctx, this.env, ws); }
}

export default { fetch: (request, env, ctx) => worker.fetch(request, env, ctx) };
