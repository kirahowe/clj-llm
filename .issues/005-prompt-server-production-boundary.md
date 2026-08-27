# Keep the prompt-server example out of production

- **Status:** Accepted example limitation; regression guarded; reopen if scope expands
- **Priority:** Low while bound to loopback
- **Alpha disposition:** Not a release blocker

## Problem

The prompt-server example intentionally demonstrates only the smallest Ring wrapper around `llm/generate`. It now binds to `127.0.0.1` and rejects request bodies over 64 KiB, but it has no authentication, authorization, rate limiting, request-level model budget, concurrency limit, or end-to-end provider deadline.

These omissions are acceptable for a local demonstration. They become security and cost vulnerabilities if the example is copied into a remotely reachable deployment or changed to bind to a non-loopback interface.

## Evidence

- `examples/prompt-server/src/example/prompt_server.clj:8-25` reads at most
  64 KiB plus one byte and returns `413` before `llm/generate` when oversized.
- `examples/prompt-server/src/example/prompt_server.clj:27-30` hard-codes Jetty
  to `127.0.0.1`.
- `examples/prompt-server/test/example/prompt_server_test.clj:14-38` exercises
  the actual Ring handler at exactly 64 KiB and at 64 KiB plus one, including
  response status/body and the no-model-call invariant for rejection.
- `examples/prompt-server/test/example/prompt_server_test.clj:40-48` invokes
  `-main` with an isolated Jetty substitute and verifies the handler plus exact
  loopback launch options without opening a port.
- `examples/prompt-server/deps.edn:4-6` and
  `examples/prompt-server/README.md:30-34` provide the example-local
  `clojure -M:test` command.
- `examples/prompt-server/README.md:36-42` rejects production use, enumerates
  the missing controls, and states that a reverse proxy alone is insufficient.
- `notebooks/examples.md:96-150` shows the guarded, loopback-only source and
  repeats the production boundary in the source notebook.

## Reopen conditions

Treat this as an active high-priority issue if any of the following occurs:

- The example gains a configurable host or binds beyond loopback.
- Documentation presents it as a deployment starting point.
- It accepts provider selection, tools, system prompts, or credentials per request.
- It is packaged or tested as a persistent service rather than a local demonstration.

## Required production controls

Before any reopen condition ships, define and implement:

1. Authentication and per-principal authorization.
2. Request and token/cost quotas, rate limiting, and bounded concurrency.
3. Whole-request deadlines and cancellation that propagate to provider work.
4. Structured error handling without provider-body or credential leakage.
5. Safe logging and interaction-record retention rules for prompts and model output.
6. Deployment guidance for TLS, reverse proxies, trusted headers, and secret management.

## Acceptance criteria

- The example remains loopback-only unless all required production controls are deliberately designed.
- A regression test or smoke check verifies loopback binding and the 64 KiB body boundary.
- README language continues to reject production use rather than implying that a reverse proxy alone makes the handler safe.
