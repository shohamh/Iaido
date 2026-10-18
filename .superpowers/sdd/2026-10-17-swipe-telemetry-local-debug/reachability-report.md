# Collector reachability indicator report

Commit: a7bca66 (+ screenshots/report commit) on feature/swipe-telemetry. Not pushed.

## Server
- RED: tests/test_health.py first run -> `GET /v1/health` returned 405 (it hit `DELETE /v1/{plane}`), 1 failed / 3 passed.
- GREEN: added `@app.get("/v1/health")` before `/v1/installations` in app.py (still behind IngestionBoundaryMiddleware: plain http => 426). `python -m pytest tests -q` -> 125 passed.

## App
- RED: `:app:testDebugUnitTest --tests '*CollectorReachabilityTest*'` -> compile errors "Unresolved reference 'CollectorReachabilityProbe' / 'CollectorReachability'".
- GREEN after CollectorReachability.kt: one failure (hung connection: structured child kept caller waiting past the deadline); fixed by running the probe in a detached scope; then BUILD SUCCESSFUL, 12 tests pass.
- Full `:app:testDebugUnitTest` BUILD SUCCESSFUL.
- Tests cover: blank URL (no connection), 200 ok (GET /v1/health, no Authorization header, no redirects, 5 s timeouts), 500, 302, 200 bad body, UnknownHost, SocketTimeout, ConnectException, SSL/IO, hung connection (overall timeout), status text for every state, error-colour predicate.
- Design: `CollectorReachabilityProbe(baseUrl).checkReachability()` in CollectorReachability.kt (new file, not in TelemetryTransport since the transport needs credentials/installation store). Pure `collectorStatusText` / `collectorStatusIsError`. UI: status row + "Check now" at top of Privacy & diagnostics; runs on first composition and on tap; "checking…" while in flight; button disabled while checking or when not configured.

## Emulator (IaidoApi35, emulator-5554, hosts entry per B9)
- Rebuilt telemetry container from this worktree (`docker compose -p iaido-swipe-e2e --env-file <scratch env from running containers> up -d --build --no-deps telemetry`); `curl https://shoham-laptop.tail555d2b.ts.net/v1/health` -> `{"status":"ok"}`.
- Debug APK installed, SettingsActivity scrolled to section:
  - docs/superpowers/diagnosis/2026-10-18-collector-indicator-reachable.png ("Collector: reachable")
  - after `docker compose -p iaido-swipe-e2e stop` + Check now: docs/superpowers/diagnosis/2026-10-18-collector-indicator-unreachable.png ("Collector: can't reach (unexpected response 502)", error colour; tailscale serve answers 502 when the backend is down).
- Collector restarted afterwards; health returns ok again.
