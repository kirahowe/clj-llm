# Document and verify Ollama readiness failures

- **ID:** ONB-010
- **Status:** Open
- **Priority:** P0 first-run diagnostics
- **Alpha disposition:** Must be completed before the next alpha onboarding is published

## Problem

A valid config proves only that the request can be resolved; it does not prove
that Ollama is listening or that the selected model is installed. Current
onboarding says to install Ollama and pull a model, but gives no readiness check
or concrete interpretation of the failures a user sees when the daemon is
stopped or the model is missing.

The HTTP layer already exposes typed errors rather than hiding operational
failures. The work is to smoke-test the real paths, document them, and improve
wording only if observed output is not actionable. A generic readiness API or
automatic daemon/model mutation would add semantics that the first real request
still cannot guarantee.

## Current evidence

- `src/clj_llm/providers/ollama.clj:134-151` defaults to
  `http://localhost:11434` and sends generation directly to `/api/chat`; there
  is no preceding health or model probe.
- `src/clj_llm/provider.clj` gives adapters default identity/no-op lifecycle
  methods. Provider `start` is lifecycle composition, not readiness.
- `src/clj_llm/http.clj:201-220` throws `:llm/http-error` with `:status`, `:url`,
  and parsed `:body` for non-2xx responses, and `:llm/network-error` with
  `:url` and the original cause for I/O failures.
- `README.md:41-46` says to pull `llama3.2` and start a REPL, but does not show
  how to prove daemon or model readiness before the first generation.
- The Ollama example READMEs say the daemon/model must be ready but do not show
  the stopped, missing-model, and successful outcomes.

## Design work

1. Run and record three focused smoke scenarios against the documented Ollama
   version/model and default localhost URL:
   - daemon stopped, then one `generate` call;
   - daemon ready but a deliberately nonexistent model selected, then one
     `generate` call;
   - daemon ready with the documented model installed, then one successful
     `generate` call.
2. For each failure, capture the user-visible exception message and safe
   `ex-data` shape. Confirm stopped-daemon failure remains
   `:llm/network-error`; confirm model rejection remains `:llm/http-error` with
   its actual status and parsed Ollama body. Do not write assertions around an
   assumed status/body until the real supported Ollama response is observed.
3. Add a short preflight sequence before onboarding code:

   ```sh
   ollama pull llama3.2
   ollama list
   curl -fsS http://localhost:11434/api/version
   ```

   Explain that `ollama list` proves the CLI can reach its daemon and shows
   installed models, while `/api/version` proves the configured default HTTP
   endpoint responds. Mention `ollama serve` only for installations where a
   desktop app or service manager has not already started Ollama.
4. Add a compact diagnostic table mapping observed symptoms to the typed data
   callers should inspect: `(:type (ex-data e))`, `:status`, `:url`, `:body`,
   and the original cause. Distinguish daemon unreachable, model absent, and a
   different provider HTTP rejection.
5. Change library error wording only when the smoke evidence identifies a
   specific ambiguity. Preserve the current type keywords and data keys; add no
   speculative remapping based solely on status code or body text.

This issue owns Ollama operational setup, smoke evidence, and error wording.
ONB-007 owns configuration size; the security endpoint policy remains separate
and must continue allowing explicit local HTTP.

## Non-goals and guardrails

- Do not add a generic `diagnose`, `ready?`, boolean health check, or provider
  SPI method for this alpha. A successful preflight races the real request, and
  many hosted providers have no free or authoritative readiness endpoint.
- Do not automatically start or stop Ollama, pull/delete models, retry, switch
  models/providers, or mutate user configuration.
- Do not turn provider `start` into a health check or describe it as one.
- Do not collapse typed failures into `false`, catch them silently, or remove
  `:status`, `:url`, `:body`, or the network cause from caller-visible errors.
- Do not infer “model missing” for every 404; document the actual Ollama body
  observed and preserve the general HTTP contract.

## Acceptance criteria

- [ ] The stopped-daemon smoke path is recorded and demonstrates a clear
  `:llm/network-error` with safe URL/cause information.
- [ ] The ready-daemon/missing-model smoke path is recorded and demonstrates
  the actual `:llm/http-error` status and parsed Ollama error body.
- [ ] The ready-daemon/installed-model path returns a normal full `generate`
  response using the exact model named by onboarding.
- [ ] Root onboarding and each Ollama example show concrete daemon/model
  preflight commands before the first generation command.
- [ ] Documentation explains how to inspect typed exception data and maps each
  observed path to an actionable operator step without exposing secrets.
- [ ] Any changed error text is justified by recorded smoke output and retains
  the existing `:llm/network-error`/`:llm/http-error` contracts and keys.
- [ ] Focused coverage protects typed failure data if implementation wording
  changes; it does not mock a new readiness API.
- [ ] No `diagnose`, boolean readiness, automatic daemon control, model
  mutation, retry, or fallback behavior is introduced.
