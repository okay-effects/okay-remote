package okay.remote.web

/** THE PAGE'S MARKS, beside okay-ui's skin: a bar at the top, the places at the bottom (the thumb's), the session as a
 * conversation — your words on the right, the agent's on the left, its calls as cards marked by the policy */
object Css:
  val marks: String =
    """|:root { --or-pass: #1e7b3c; --or-block: #c0392b; --or-wait: #b7791f;
       |  --or-pass-soft: color-mix(in srgb, var(--or-pass) 10%, var(--okay-surface));
       |  --or-block-soft: color-mix(in srgb, var(--or-block) 9%, var(--okay-surface));
       |  --or-wait-soft: color-mix(in srgb, var(--or-wait) 14%, var(--okay-surface)); --or-r: 14px; }
       |@media (prefers-color-scheme: dark) { :root:root { --or-pass: #4cc477; --or-block: #ff7b6b;
       |  --or-wait: #f0b84a; } }
       |body .okay-face { padding: 0 12px 96px; max-width: 760px; margin: 0 auto; }
       |body .okay-face .okay-view > .okay-group { gap: 10px; }
       |body .okay-face .okay-mark { display: block; }
       |body .okay-face .okay-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
       |body .okay-face .okay-heading-1 { position: absolute; width: 1px; height: 1px; overflow: hidden;
       |  clip: rect(0 0 0 0); white-space: nowrap; }
       |[class*="okay-mark-place-"] { display: none !important; }
       |.okay-mark-top { position: sticky; top: 0; z-index: 6; margin: 0 -12px; padding: 10px 12px;
       |  padding-top: calc(10px + env(safe-area-inset-top));
       |  background: color-mix(in srgb, var(--okay-bg) 88%, transparent); backdrop-filter: blur(10px);
       |  border-bottom: 1px solid var(--okay-border); }
       |.okay-mark-top > .okay-group { flex-direction: row; align-items: center; justify-content: space-between; }
       |.okay-mark-brand p { font-weight: 700; letter-spacing: -0.01em; }
       |[class*="okay-mark-live-"] p { font-size: 13px; color: var(--okay-muted); display: flex; align-items: center;
       |  gap: 6px; }
       |[class*="okay-mark-live-"] p::before { content: ""; width: 8px; height: 8px; border-radius: 50%;
       |  background: var(--or-wait); }
       |.okay-mark-live-on p::before { background: var(--or-pass); box-shadow: 0 0 0 3px var(--or-pass-soft); }
       |.okay-mark-live-off p::before { background: var(--or-block); }
       |body .okay-face .okay-mark-slot .okay-mark { display: inline-flex; }
       |body .okay-mark-slot > .okay-group { gap: 6px; flex-direction: row; flex-wrap: wrap; }
       |body .okay-mark-button > .okay-ref { min-height: 44px; padding: 6px 16px; font-size: 15px; }
       |
       |/* the places: a bar at the bottom */
       |.okay-mark-places { position: fixed; left: 0; right: 0; bottom: 0; z-index: 5;
       |  background: color-mix(in srgb, var(--okay-surface) 92%, transparent); backdrop-filter: blur(10px);
       |  border-top: 1px solid var(--okay-border); padding: 4px 4px calc(4px + env(safe-area-inset-bottom)); }
       |.okay-mark-places .okay-mark-slot > .okay-group { flex-wrap: nowrap; justify-content: space-around; gap: 0; }
       |.okay-mark-places .okay-mark-slot > .okay-group > * { flex: 1 1 0; min-width: 0; }
       |.okay-mark-places .okay-mark-button > .okay-ref { border: 0; background: none; color: var(--okay-muted);
       |  min-height: 52px; width: 100%; padding: 4px 2px; font-size: 13px; font-weight: 600; box-shadow: none; }
       |.okay-mark-places .okay-mark-button-primary > .okay-mark-button > .okay-ref,
       |.okay-mark-places .okay-mark-button-danger > .okay-mark-button > .okay-ref { background: none !important;
       |  border: 0 !important; box-shadow: none !important; }
       |.okay-mark-places .okay-mark-button-primary > .okay-mark-button > .okay-ref { color: var(--okay-accent);
       |  font-weight: 800; }
       |.okay-mark-places .okay-mark-button-danger > .okay-mark-button > .okay-ref { color: var(--or-block); }
       |
       |/* nothing inside a mark is a card of its own, unless said below */
       |body .okay-face .okay-mark .okay-mark > .okay-group { background: none; border: 0; padding: 0;
       |  box-shadow: none; gap: 4px; }
       |.okay-mark-small p, .okay-mark-row-small p { font-size: 13px; color: var(--okay-muted); }
       |
       |/* the sessions: a row each, its title the button */
       |body .okay-face [class*="okay-mark-row-"] > .okay-group { border: 1px solid var(--okay-border);
       |  border-left: 4px solid var(--okay-border); border-radius: var(--or-r); padding: 8px 12px;
       |  background: var(--okay-surface); gap: 0; }
       |body .okay-face .okay-mark-row-wait > .okay-group { border-left-color: var(--or-wait);
       |  background: var(--or-wait-soft); }
       |body .okay-face .okay-mark-row-busy > .okay-group { border-left-color: var(--okay-accent); }
       |body .okay-face .okay-mark-row-idle > .okay-group { border-left-color: var(--or-pass); }
       |[class*="okay-mark-row-"] .okay-mark-button > .okay-ref { border: 0 !important; background: none !important;
       |  padding: 2px 0; min-height: 32px; font-weight: 600; color: var(--okay-fg) !important; text-align: left;
       |  box-shadow: none !important; justify-content: flex-start; white-space: normal; }
       |
       |/* the conversation */
       |.okay-mark-session-head p:first-child { font-weight: 700; font-size: 15px; display: -webkit-box;
       |  -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
       |body .okay-face .okay-mark-you > .okay-group { max-width: 86%; margin-left: auto;
       |  background: var(--okay-accent);
       |  border-radius: 18px 18px 6px 18px; padding: 10px 14px; border: 0; }
       |.okay-mark-you p { color: #fff; }
       |body .okay-face .okay-mark-agent > .okay-group { max-width: 92%; background: var(--okay-surface);
       |  border: 1px solid var(--okay-border); border-radius: 18px 18px 18px 6px; padding: 10px 14px; }
       |body .okay-face [class*="okay-mark-call-"] > .okay-group { background: var(--okay-surface);
       |  border: 1px solid var(--okay-border); border-left: 4px solid var(--okay-border); border-radius: 12px;
       |  padding: 8px 12px; gap: 4px; }
       |body .okay-face .okay-mark-call-pass > .okay-group { border-left-color: var(--or-pass); }
       |body .okay-face .okay-mark-call-block > .okay-group { border-left-color: var(--or-block);
       |  background: var(--or-block-soft); }
       |body .okay-face .okay-mark-call-wait > .okay-group { border: 2px solid var(--or-wait);
       |  background: var(--or-wait-soft); box-shadow: 0 6px 20px color-mix(in srgb, var(--or-wait) 22%, transparent); }
       |.okay-mark-call-head p { font-weight: 700; font-size: 13px; }
       |.okay-mark-call-gist p { font: 13px/1.45 ui-monospace, Menlo, monospace; }
       |.okay-mark-call-word p { font-size: 12.5px; font-weight: 600; color: var(--okay-muted); }
       |.okay-mark-call-pass .okay-mark-call-word p { color: var(--or-pass); }
       |.okay-mark-call-block .okay-mark-call-word p { color: var(--or-block); }
       |.okay-mark-call-wait .okay-mark-call-word p { color: var(--or-wait); }
       |body .okay-face .okay-mark-result > .okay-group, body .okay-face .okay-mark-result-error > .okay-group {
       |  background: var(--okay-bg); border-radius: 8px; padding: 6px 8px; }
       |.okay-mark-result p, .okay-mark-result-error p { font: 12px/1.45 ui-monospace, Menlo, monospace;
       |  color: var(--okay-muted); max-height: 60vh; overflow: auto; }
       |.okay-mark-result-error p { color: var(--or-block); }
       |.okay-mark-unfold .okay-mark-button > .okay-ref { min-height: 28px; padding: 0 6px; border: 0;
       |  background: none; color: var(--okay-accent); font-size: 13px; box-shadow: none; }
       |body .okay-face .okay-mark-end > .okay-group { border-top: 1px dashed var(--okay-border); padding-top: 6px; }
       |body .okay-face .okay-mark-end-failed > .okay-group { border-top: 1px dashed var(--or-block);
       |  padding-top: 6px; }
       |.okay-mark-composer textarea { min-height: 64px; font-size: 16px; }
       |.okay-mark-composer .okay-mark-slot > .okay-group { flex-wrap: nowrap; }
       |.okay-mark-policy textarea { min-height: 60vh; font: 13px/1.45 ui-monospace, Menlo, monospace; }
       |.okay-mark-new textarea { min-height: 120px; font-size: 16px; }
       |.okay-mark-qr { text-align: center; }
       |body .okay-face .okay-mark-diff > .okay-group { background: var(--okay-bg); border-radius: 8px;
       |  padding: 6px 8px; overflow-x: auto; }
       |.okay-mark-diff p { font: 12px/1.45 ui-monospace, Menlo, monospace; white-space: pre; overflow-wrap: normal; }
       |body .okay-face .okay-mark-pairing > .okay-group { padding: 28px 18px; border: 1px dashed var(--okay-border);
       |  border-radius: var(--or-r); gap: 12px; }
       |.okay-mark-pairing p:first-child { font-weight: 700; font-size: 18px; }
       |.okay-mark-address p { font: 12px ui-monospace, Menlo, monospace; overflow-wrap: anywhere; }
       |""".stripMargin
