# Runtime unsupported RPC report

## Delivered

- Unknown and explicitly unavailable host RPC methods now return the structured
  `UNSUPPORTED_HOST_CAPABILITY` error. The error message includes both request ID and method.
- Added callable JavaScript compatibility namespaces for extension management,
  administrator/server filesystem access, top-level jQuery DOM access, and unmapped
  SillyTavern backend routes. These all route through the RPC bridge and reject rather
  than throwing a JavaScript `TypeError`.
- The runtime bridge now returns structured errors for oversized requests and malformed
  requests when a valid callback is available, retains callback validation, and caps
  serialized responses at 1 MB with `RESPONSE_TOO_LARGE`.
- Hidden browser sessions pass their trusted `script.id` into the bridge. Every completed
  browser-script RPC records an `rpc` diagnostic with method, duration, and sanitized error.
  Message frontends retain no script identity and are not attributed.
- Preserved the original trailing-lambda bridge constructor form.

## TDD evidence

RED was observed with 7 focused failures before implementation: unsupported code/message,
explicit categories, JavaScript shims, oversized request handling, response cap, and script
diagnostics. A separate compile-time regression exposed that placing new parameters after
the callback broke the pre-existing trailing-lambda constructor style; the final signature
puts `emitResult` last and has direct regression coverage.

## Verification

- `:app:testDebugUnitTest` for `TavernRuntimeControllerTest`, `TavernRuntimeScriptTest`,
  `TavernRuntimeBridgeTest`, `TavernRuntimeModelsTest`, and `TavernScriptDiagnosticsTest`: PASS.
- `:app:compileDebugKotlin`: PASS.
- `git diff --check`: PASS.

## Scope notes

- No unsupported host capability was implemented.
- `verification-screenshots/` was not modified or staged.
