# Browser interaction contract

Browser tools run inside the MiniAgent tool pipeline. The model may choose an action and a target, but cannot choose another owner's browser session.

## Session ownership

The tool registration boundary derives the browser resource key from the authenticated tenant, user, and chat held by `OwnerContext` and `WorkspaceContext`. A missing identity fails before dispatch. The legacy `sessionId` argument may be omitted, `default`, or the current chat ID; other values are rejected. `default` is a compatibility input alias, not a shared browser resource.

All Playwright object creation and access happen on one dedicated browser actor thread. The actor explicitly uses the application class loader so that Playwright driver resources remain visible when a packaged Spring Boot application is called from executor workers. Tools from different request workers never call Playwright objects directly. This serializes first-version browser operations; it does not claim parallel independent browser actors or multi-tab control.

## Authentication recovery

Each authenticated tenant/user/chat identity owns a separate Playwright browser context. Cookies and localStorage are restored from `AgentDataPaths.root()/browser-sessions` before creating a page. State is saved after every browser operation and before ordinary close/shutdown. `browser_close` preserves authentication; `browser_clear_session` explicitly deletes only this chat's saved authentication and closes its live context. The clear tool is a local write, requires the existing browser permission policy, is unavailable to read-only/unapproved-plan sessions, and never retries automatically.

Saved state is AES-GCM encrypted with a local 256-bit key and the trusted identity as authenticated additional data. The directory, key and state files are owner-only (Windows ACL or POSIX permissions). Writes use a same-directory temporary file, force the content, and atomically replace the target. Symlinks/non-regular files, invalid identities, oversized data, corrupt ciphertext and a missing key while saved states exist are rejected. Corrupt state must be explicitly cleared; opening a browser does not silently replace it with a logged-out state.

Disk failures preserve the actual browser action result and live context. A `statePersistenceWarning` (or a warning appended to a legacy text response) explains that recovery is unavailable; it does not turn an already-performed click/input into a retryable failure. Failed context closure retains its handle for another explicit close/clear. If saved authentication was already deleted before closure failed, the old context is locked against normal actions and cannot save state, including during shutdown; only an explicit close/clear can finish cleanup. A disconnected browser is recreated from the last successfully saved state. Recovery never automatically revisits a URL, since loading a business page may itself have effects.

This version persists cookies and localStorage through Playwright storageState. It does not restore sessionStorage, IndexedDB, open tabs, page DOM, pending requests, expired server sessions or credentials revoked by the site. Sites may still require reauthentication. Storage encryption protects files at rest in the owner-only directory; it does not isolate secrets from another process already running as that OS user.

## Request-level network checks

Every newly created browser context blocks Service Workers and installs HTTP(S) and page WebSocket routes before creating its page. Each routed HTTP(S) URL is checked by the existing `NetworkGuard`; denied requests and unavailable boundaries abort before connection. This includes frame documents, images/scripts/styles, page fetch/XHR and the dedicated-worker HTTP requests exercised by the fixture. Navigation entry checks and frame-access checks remain in place. Denied diagnostics contain only a bounded category and origin, without path/query credentials.

The Playwright route API does not guard subsequent requests in an HTTP redirect chain. For an established page/renderer target, an additional Chromium CDP `Fetch` response-stage boundary is installed before navigation and checks each redirect `Location` before releasing that response, preserving ordinary allowed redirects, HTTP methods, cookies and the browser's final URL. It does not fetch or replay an established page's POST. A denied redirect is aborted before its destination receives a request.

Some requests arrive before a response boundary can be installed: an initial popup can have no Frame yet, and an out-of-process iframe's request may already be paused when its CDP target becomes visible. These surfaces permit a single GET/HEAD response with automatic redirects and retries disabled. They reject redirects and other methods instead of following an unchecked chain. This is an explicit compatibility limit: popup login redirects and out-of-process iframe submissions may be unavailable. Ordinary established-page and same-renderer iframe redirects continue to work. Failure never resumes or resends the intercepted request.

`routeWebSocket` validates page WebSocket endpoints through `NetworkGuard.validateWebSocketUrl` before connecting, retaining Playwright's normal message forwarding. The Chromium fixture checks an allowed handshake, a denied handshake, and refusal to follow an HTTP 302 handshake to an unchecked endpoint. Worker-created WebSockets, WebRTC/WebTransport, browser-internal requests and arbitrary extensions are not claimed as covered by these hooks. This is a browser-tool boundary, not a host network sandbox.

Playwright Java is pinned to 1.59.0, the first release whose WebSocket route implementation accepts optional close-code/reason fields; 1.49–1.58 throw NullPointerException for ordinary `ws.close()` or page teardown. The runtime reads its required browser revision from the current driver-bundle's `browsers.json` instead of accepting an unrelated older cache. Windows validation accepts the current Chrome-for-Testing and legacy directory layouts. The corresponding headless Chromium revision is 1217; packaging must validate the dependency-matched revision when reusing browser assets.

Driver creation explicitly skips implicit browser downloads. The existing `ensureChromiumInstalled` path alone owns mode-specific installation, so starting a headless browser does not silently download Firefox, WebKit or full Chromium.

The Java installer uses matching `playwright`, `driver` and `driver-bundle` jars on its subprocess classpath. Its npm fallback pins `playwright@1.59.0` and retains `--only-shell` for headless mode. All installer paths verify the required revision and critical files after an exit code of zero; a different cached revision or incomplete download does not count as success.

Each installer backend has a ten-minute process deadline starting when it is launched. Output is drained in bounded batches while waiting; a child keeping stdout open cannot delay that deadline. Completion, timeout, cancellation and exceptions close streams and clean up the installer and ordinary descendant processes observed during execution, with at most two seconds of additional cleanup waiting. Cancellation preserves the interrupt flag and prevents another fallback installer or subsequent Playwright initialization. The three fallback backends retain separate deadlines; this is not an OS job or sandbox boundary, and does not impose new deadlines on arbitrary Playwright calls.

Image extraction's `APIRequestContext` is outside browser routing, so it independently validates the image URL and disables automatic redirects before downloading; unsupported images fall back to a screenshot. DNS validation and Chromium's actual connection remain separate operations: this change does not claim to eliminate DNS rebinding. Strong host-wide egress isolation still requires a separate network boundary.

## Observe and act

Snapshots return an observation ID and opaque refs such as `b12.3`. Each ref is attached to an observed DOM `ElementHandle`. Pure line numbers are no longer executable refs. Clicking or typing through a ref checks the original URL, accessible snapshot and node connectivity before sending input. A changed document or a detached/replaced target returns `CONFLICT` with `STALE_OBSERVATION`; the agent must observe again.

Successful actions return a fresh observation and invalidate prior refs. Exact text, role, label and CSS actions require one matching element. Typing never falls back to the first input on the page. A timed-out click never triggers an automatic JavaScript click, because the original click may already have taken effect.

This guards against line-number drift and identical-node replacement. It cannot freeze a live web page between observation and dispatch. A page can change during native input; the next observation remains necessary to verify the business outcome.

## Cancellation and uncertain outcomes

An interrupted operation that is still queued is marked cancelled and cannot later send input. An operation already dispatched to Playwright is allowed to finish on its actor; interruption is reported as `UNKNOWN`, rather than claiming that the input was undone. The outer pipeline must not immediately retry it. This first version provides a safe boundary between actions, not rollback of clicks already delivered to a website.

`browser_click` first runs a Playwright `trial` against the same element handle, with a three-second actionability budget. Trial checks visibility, stability, enabled state and the hit target without sending the click; failure at this boundary returns `FAILED` / `CONFLICT`, `inputDispatched=false`, a fresh observation when available and an instruction to observe before choosing a target. Initialization and target-resolution failures have the same no-input classification. A successful trial does not freeze the page: observed refs and element attachment are checked again before input. There is exactly one actual click call, with a separate five-second budget, followed by an observation with a three-second budget. The implementation does not infer delivery from exception text, force the click, execute a fallback JavaScript click or automatically replay input. Once the actual click API has been invoked, navigation/dispatch/observation failures remain `UNKNOWN`; `inputDispatched=true` means the input boundary was entered, not proof of business success. A state change between trial and actual input can therefore still produce an unknown outcome. Failure responses contain a concise message, phase and diagnostic ID; the full SDK call log and stack stay in server logs under that ID.

Each actor call also owns a preparation/input gate. Interrupting its waiting caller marks that gate stopped without interrupting the shared Playwright thread. A click checks the gate before and after trial, then atomically claims the input boundary immediately before actual click dispatch; a stop that wins the gate prevents late input when an overlay disappears or the trial finishes. Cancellation after the input boundary remains `UNKNOWN`, and the caller conservatively retains `UNKNOWN` while the actor finishes its current preparation. The stop marker belongs only to that call and cannot poison later browser operations. Trial can scroll the page but does not request modifiers or send a click; scrolling is not presented as rollback of page-side effects.

Execution-control causes and tool outcomes are carried separately. A known cancellation, execution deadline or quota stop retains its original cause through both ReAct and bound DAG actions, even when the tool outcome is `UNKNOWN`. Such a stop cannot be accepted as task success or enter automatic node recovery. A pre-dispatch stop remains resumable; an unconfirmed dispatched action retains evidence and is isolated from automatic replay. Ordinary tool timeouts and provider rate limits do not by themselves imply an execution-control stop.

Navigation, snapshot, screenshot and extraction errors return structured failures (`success:false`) across the string tool boundary. Interactive or script execution errors with unconfirmed effects remain `UNKNOWN` and non-retriable; a plain error string must never be parsed as a successful tool result.

## Screenshots

`browser_screenshot` writes a unique PNG under the supplied trusted workspace and returns JSON:

```json
{
  "success": true,
  "message": "截图已保存……",
  "path": "<absolute workspace path>",
  "url": "https://example.com/",
  "tool_images": [{"path":"<same absolute path>","mimeType":"image/png"}]
}
```

The model-message projection layer must validate workspace ownership and image limits before sending the image to a vision-capable model. A screenshot path in text alone is not a visual observation.

## Form workflow

The form tools participate in the same owner-bound browser session and use current observation refs. `browser_form_inspect` reads field labels, control types, constraints, options and current state. `browser_form_verify` reads one field and compares it with the supplied expected value/state/files. These are read-only operations and remain available to read-only or unapproved-plan sessions.

`browser_select`, `browser_set_checked` and `browser_upload` modify the page and are external actions: selecting a file or changing a value can immediately trigger network requests or autosave. They require the existing permission policy for browser actions, serialize within the browser session and have no automatic retry. A handler deadline or unconfirmed effect yields `UNKNOWN`; inspect actual page state before deciding what to do next. Upload paths must resolve inside the current workspace or current authenticated user's upload directory, including real-path checks, regular-file checks and a limit of 10 files / 50 MiB total. Arbitrary local files and other users' attachments are rejected.

| Tool | Arguments | Confirmed result |
| --- | --- | --- |
| `browser_form_inspect` | no required arguments | Fields with current `ref`, label/type, native constraints, options, actual state and validity |
| `browser_select` | `ref`, string array `values`, `by=value` or `label` | Actual selected values match; supports native single/multiple selects and uniquely owned single-select ARIA listboxes |
| `browser_set_checked` | `ref`, boolean `checked` | Actual native checkbox/radio or explicit ARIA checkbox/radio/switch state matches; setting the same state does not toggle |
| `browser_upload` | `ref`, string array `paths` | Actual input files match names and sizes; server receipt remains a separate check |
| `browser_form_verify` | `ref`, optionally one of `expectedValue`, `expectedChecked`, `expectedValues`, `expectedFiles` | Actual field value/state matches and current browser/ARIA constraints pass |

Inspection includes hidden file inputs and excludes hidden data inputs. Password values are hidden in both field metadata and emitted snapshots. Form refs additionally bind field values and constraints, so a silent value/constraint change invalidates them. A replaced DOM node is rejected even if its accessible description remains identical. Inspection and verification do not call `checkValidity` or `reportValidity`, which would dispatch validation events.

Inspection shares a 200-candidate limit across at most 64 attached frames, including nested/cross-origin frames and open shadow roots, and reports truncation. Each field reports its current frame path/name/URL. Frame paths describe this observation only; actions use opaque refs bound to the actual element and document. Reloading or replacing a frame invalidates its refs even at the same URL. SVG/XML frames without an HTML body do not block parent form inspection. Frame URLs and their embedding chain must pass the existing NetworkGuard policy; this read/action check does not claim request-level interception of every browser resource.

Custom controls expose ARIA state without inheriting native validity semantics. Selection requires one explicitly owned listbox, a unique option and explicit aria-selected state; unlinked, ambiguous or multi-select custom widgets are rejected. ARIA checkbox/radio/switch actions require explicit true/false states and verify the result; mixed states are rejected. Open-shadow labels and descriptions are resolved inside their own root. Hidden/inert fields are excluded, while hidden file inputs remain discoverable for uploads.

Fill native controls using the appropriate typed tool, read back the actual value and check validation errors. Other custom widgets, including date pickers and unsupported dropdowns, use the snapshot/click/key loop with explicit readback. Never echo password values. A field check only establishes the current state of that field; it does not prove the whole form is correct or a business submission succeeded. Submit within the user's authorized scope, then inspect the actual confirmation receipt or business identifier.

## Verification

Unit tests cover trusted identity, cross-session rejection, stale refs, detached nodes, actor threading, queued cancellation and URL identity. `BrowserControlIntegrationTest` uses real Chromium and an ephemeral local HTTP fixture when `miniagent.browser.integration=true`; it verifies form input, click outcome, per-session pages, stale refs and readable PNG output. `BrowserFrameFormsIntegrationTest` covers cross-origin/nested frames, frame replacement, native form actions, explicit ARIA controls and non-HTML embeds. `BrowserSessionRecoveryIntegrationTest` verifies close/recreation/disconnection recovery, owner isolation and explicit clearing against the real browser; storage/policy tests exercise corruption, write failures and failed-close cleanup. Only test fixtures use a permissive mocked `NetworkGuard`; production frame reads additionally validate the existing URL policy.

CI and local runs use `node scripts/run-browser-contracts.mjs`. It discovers every test in the App reactor marked with `miniagent.browser.integration=true`, including the client media playback/history tests; new marked classes do not need a separate CI class-list edit. `--list` and `--dry-run` only inspect selection and arguments. `--evidence-dir=<new-directory>` selects a fresh output directory for the manifest, Surefire XML and UI screenshots. The gate refuses missing, empty, skipped or failed class reports, even if Maven exits zero. Paid-model and native-desktop opt-ins are excluded. Prepare the dependency-matched Chromium materials before running; this gate does not install browsers or contact model providers.
