# Theory and ACP status polish

## Scope

Polish the server-owned theory view and make fallback diagnostics discoverable without changing the learning workflows or probing ACP merely because a page was opened.

## Theory view

- Remove the entire trailing `СЕРВЕРНАЯ ПРОГРАММА` section from the theory route.
- Render every evidence item as two columns on desktop and one column on narrow screens.
- The first column contains the evidence grade, title, and optional source link. The second column contains the evidence text.
- Preserve external-link safety with `target="_blank"` and `rel="noopener noreferrer"`.

## ACP status and diagnostics

- The compact header status remains informational and non-interactive.
- The lower `ACP agent` card in the learning sidebar becomes a keyboard-accessible button-like control.
- Activating that card opens a native dialog showing the current ACP state, configured agent command, and a safe diagnostic reason.
- The system status response distinguishes configured (`acpEnabled`) from currently available (`acpAvailable`) and includes a nullable diagnostic reason.
- When ACP is disabled by configuration, the reason explicitly names `ACP_ENABLED=false`.
- After an ACP runtime failure, the gateway retains a sanitized last-failure message for subsequent status requests. A successful ACP answer clears that runtime failure.
- Without an attempted runtime call, configured ACP is treated as available; status loading itself does not perform a probe.
- Merely loading system status never launches an ACP process or creates a session.
- The UI provides a useful generic explanation when the backend is unreachable or no detailed reason exists.

## Error handling and privacy

- Do not return stack traces or exception class dumps to the browser.
- Normalize blank failure messages to a generic Russian explanation.
- Keep fallback operation unchanged; this change only exposes why ACP is unavailable.

## Verification

- Frontend structural tests assert removal of the trailing theory section, two-column evidence markup, and dialog/card accessibility.
- Backend tests assert the configured-disabled reason and safe runtime-failure status behavior.
- Run the frontend Node tests and the backend Maven test suite before merging the feature branch into `main`.
