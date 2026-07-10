# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Changed (pre-release API finalization)
- **Dropped the `kirahowe.` prefix from all namespaces**: they are now
  `clj-llm.core`, `clj-llm.eval`, `clj-llm.provider`, ... (the Maven
  artifact remains `com.kirahowe/clj-llm`); the integrant key is
  `:clj-llm/config`.
- **Keyspace policy.** Every library-defined key in maps users author or
  store (config, requests, responses/records, cases, variants, suites,
  reports) is namespaced `:clj-llm/...`; unqualified and user-namespaced
  keys in those maps are reserved for users forever. Protocol structures
  (messages, tool definitions, tool calls, usage, stream chunks, scorer
  results) keep plain spec'd keys whose plain keyspace is reserved.
- **HTTP moved to `java.net.http`** (JDK built-in) — clj-http and its
  Apache HttpClient dependency tree removed; the library's dependencies
  are now aero, charred and malli only.
- **Streaming chunks are type-tagged**: `:clj-llm/on-chunk` receives
  `{:type :text :text delta}`. Callbacks must ignore unknown types —
  this is how future chunk kinds (tool-call deltas, thinking, ...)
  arrive without breaking existing code.
- **Adapter multimethods take a trailing `opts` map**:
  `(generate! provider-config request opts)`, `(embed! ...)`,
  `(start provider-config opts)`, `(stop provider-config opts)` —
  reserved, empty today; signatures are frozen and future context always
  travels inside `request`/`opts`. The adapter compatibility contract is
  documented in the `clj-llm.provider` docstring.
- **OpenAI adapter emits `max_completion_tokens`** (current protocol
  field) instead of the deprecated `max_tokens`; set
  `:legacy-max-tokens? true` on the provider for older OpenAI-compatible
  servers.
- Error `:type` values are now flat `:clj-llm/...` keywords
  (`:clj-llm/http-error`, `:clj-llm/config-error`, ...), decoupled from
  internal namespace layout and frozen as public API.

### Added
- **Malli schemas for every public contract** (`clj-llm.spec`): messages,
  tools, requests, responses, config, cases, variants, suites. Requests,
  configs and suites are validated at the boundary with humanized errors
  (`:clj-llm/invalid-request`, `:clj-llm/invalid-config`,
  `:clj-llm/invalid-suite`).
- **System-level evals**: a suite's `:clj-llm/task` (function or qualified
  symbol) is what a case×variant runs — default is a single `generate`
  call; supply your own to eval a whole pipeline/agent/handler with the
  same cases, scorers and reports.
- **Score thresholds**: `:clj-llm/thresholds {scorer-id min-mean}` adds
  `:clj-llm/passed?` to reports and makes the CLI exit non-zero on
  regression — evals as a CI gate.
- **Report provenance**: reports carry `:clj-llm/run-at`,
  `:clj-llm/case-count`, `:clj-llm/variant-count`, and each variant summary
  records the model that actually served it.
- Scorers (and `:clj-llm/task`) in EDN suites may be qualified symbols,
  resolved with `requiring-resolve` at run time.
- Initial implementation: stateless `generate` and `embed` API over
  provider-agnostic config.
- Evals as a first-class concept: every response is a complete,
  replayable interaction record (`:clj-llm/request`, `:clj-llm/usage`,
  `:clj-llm/latency-ms`, `:clj-llm/started-at`, `:clj-llm/op`), an
  `:clj-llm/on-interaction` hook collects records from live traffic, and
  `clj-llm.eval` runs suites (cases × variants) with built-in scorers,
  custom scorers and model-graded `llm-judge` scoring into per-variant
  comparison summaries.
- EDN config files read with aero (`#env`, `#or`, `#profile`, ...).
- Adapters for Anthropic, the OpenAI chat-completions protocol (OpenAI,
  OpenRouter, Groq, Together, vLLM, LM Studio, ...) and Ollama's native API.
- Streaming via an `:clj-llm/on-chunk` callback (SSE and NDJSON).
- Automatic tool-calling loop for tools defined as maps with a `:fn`.
- Model aliases (`:clj-llm/models` in config) so code can name intents,
  not vendors.
- Optional Integrant bindings (`clj-llm.integrant`) with provider
  `start`/`stop` lifecycle hooks.
- Babashka tasks for all dev workflows (`bb tasks`), including `bb eval`.
- Documentation book under `notebooks/`, rendered with Clay + Quarto.

### Planned (evals roadmap — deliberately additive)
- Response caching so re-running a suite after a scorer-only change is
  cheap and reproducible (user-suppliable get/put pair).
- EDN-expressible model-graded judges, e.g.
  `{:llm-judge {:model :smart :criteria "..."}}` as a scorer form in
  suite files.
- Per-case weights, and report diffing helpers for comparing stored
  reports across time.
- A response-shape contract for tasks that return richer system output
  (retrieved documents, intermediate steps) so scorers can grade
  process, not just final text.

[Unreleased]: https://github.com/kirahowe/clj-llm/compare/...HEAD
