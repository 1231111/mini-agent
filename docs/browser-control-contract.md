# Browser interaction contract

Browser tools run inside the MiniAgent tool pipeline. The model may choose an action and a target, but cannot choose another owner's browser session.

## Session ownership

The tool registration boundary derives the browser resource key from the authenticated tenant, user, and chat held by `OwnerContext` and `WorkspaceContext`. A missing identity fails before dispatch. The legacy `sessionId` argument may be omitted, `default`, or the current chat ID; other values are rejected. `default` is a compatibility input alias, not a shared browser resource.

All Playwright object creation and access happen on one dedicated browser actor thread. The actor explicitly uses the application class loader so that Playwright driver resources remain visible when a packaged Spring Boot application is called from executor workers. Tools from different request workers never call Playwright objects directly. This serializes first-version browser operations; it does not claim parallel independent browser actors or multi-tab control.

## Observe and act

Snapshots return an observation ID and opaque refs such as `b12.3`. Each ref is attached to an observed DOM `ElementHandle`. Pure line numbers are no longer executable refs. Clicking or typing through a ref checks the original URL, accessible snapshot and node connectivity before sending input. A changed document or a detached/replaced target returns `CONFLICT` with `STALE_OBSERVATION`; the agent must observe again.

Successful actions return a fresh observation and invalidate prior refs. Exact text, role, label and CSS actions require one matching element. Typing never falls back to the first input on the page. A timed-out click never triggers an automatic JavaScript click, because the original click may already have taken effect.

This guards against line-number drift and identical-node replacement. It cannot freeze a live web page between observation and dispatch. A page can change during native input; the next observation remains necessary to verify the business outcome.

## Cancellation and uncertain outcomes

An interrupted operation that is still queued is marked cancelled and cannot later send input. An operation already dispatched to Playwright is allowed to finish on its actor; interruption is reported as `UNKNOWN`, rather than claiming that the input was undone. The outer pipeline must not immediately retry it. This first version provides a safe boundary between actions, not rollback of clicks already delivered to a website.

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
| `browser_select` | `ref`, string array `values`, `by=value` or `label` | Actual selected values match; supports native single/multiple selects |
| `browser_set_checked` | `ref`, boolean `checked` | Actual native checkbox/radio state matches; setting the same state does not toggle |
| `browser_upload` | `ref`, string array `paths` | Actual input files match names and sizes; server receipt remains a separate check |
| `browser_form_verify` | `ref`, optionally one of `expectedValue`, `expectedChecked`, `expectedValues`, `expectedFiles` | Actual field value/state matches and current browser/ARIA constraints pass |

Inspection includes hidden file inputs and excludes hidden data inputs. Password values are hidden in both field metadata and emitted snapshots. Form refs additionally bind field values and constraints, so a silent value/constraint change invalidates them. A replaced DOM node is rejected even if its accessible description remains identical. Inspection and verification do not call `checkValidity` or `reportValidity`, which would dispatch validation events.

The first version inspects up to 200 controls in the current document/open shadow roots, reports truncation, and does not traverse iframe documents. Custom controls expose ARIA state but do not inherit full native validity semantics; verification reports its coverage. Native selection/check/upload tools reject custom controls rather than guessing their behavior.

Fill native controls using the appropriate typed tool, read back the actual value and check validation errors. Custom ARIA combobox/listbox/date controls can use the existing snapshot/click/key loop. Never echo password values. A field check only establishes the current state of that field; it does not prove the whole form is correct or a business submission succeeded. Submit within the user's authorized scope, then inspect the actual confirmation receipt or business identifier.

## Verification

Unit tests cover trusted identity, cross-session rejection, stale refs, detached nodes, actor threading, queued cancellation and URL identity. `BrowserControlIntegrationTest` uses real Chromium and an ephemeral local HTTP fixture when `miniagent.browser.integration=true`; it verifies form input, click outcome, per-session pages, stale refs and readable PNG output. Only the test supplies a permissive mocked `NetworkGuard`. Production public-navigation restrictions are unchanged.
