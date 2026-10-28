// okay-remote's service worker: what makes the page installable. The page is the daemon's, always fresh: nothing is
// kept here — every request goes to the network.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
