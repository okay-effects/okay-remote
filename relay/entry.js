// the relay's entry: the Scala.js module's front and its rooms (a Durable Object), the page's files as text
import { route, setFiles, Room, Link } from "./dist/okay-remote-relay.js";
import files from "./dist/assets.js";
setFiles(files);
export { Room, Link };
export default { fetch: (request, env) => route(request, env) };
