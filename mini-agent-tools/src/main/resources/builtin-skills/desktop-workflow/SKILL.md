---
name: desktop-workflow
description: Operate native Windows desktop applications using screenshots, window handles and single approved actions; use browser tools for website workflows.
---

# Desktop workflow

Use `desktop_observe` to obtain the active window screenshot, visible window list and observation ID. The Windows adapter uses native window capture and SendInput; macOS desktop support is not enabled in this version.

Choose one `desktop_act` action from the current observation. Click coordinates are physical pixels relative to the screenshot, not screen-global or CSS coordinates. To switch applications, use `focus` with a window ID from the latest list. Every action consumes its observation and returns a new screenshot and observation ID.

Inspect the actual resulting UI. Accepted input is not proof of task completion. Do not replay stale observations or retry an unknown submission. Re-observe first, then establish whether it already succeeded.

Actions pass through MiniAgent permissions and cancellation controls. Never bypass those controls by spawning another automation program or shell. Leave operating-system consent and elevated/secure-desktop prompts to the user. When paused, interrupted or handing control back, release the desktop lease; resume with a fresh observation.

Keep clicks inside the feedback loop. A DAG node represents a business result, not each mouse or keyboard event. Use web/browser tools when the application is a website and structured elements are available.
