# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

## [0.1.0-alpha1] — 2026-08-03

Initial public alpha. The API is intended to be final; the alpha window
exists so anything that would force a breaking change can still surface
and be fixed before 0.1.0.

### Changed (pre-release API finalization)
- **`:lib/prompt` appends to `:lib/messages`** as the next user message
  (and is plain zero-shot shorthand when there are no messages).
  Previously, passing both silently dropped the prompt — a request that
  looked like "continue the conversation with this question" answered
  the bare history instead.
- **`:lib/system` wins over inline system messages in every adapter.**
  The OpenAI and Ollama adapters used to silently ignore `:lib/system`
  whenever the messages already contained a `:system`-role message;
  Anthropic did the opposite. One rule now: an explicit `:lib/system`
  replaces whatever system messages the conversation carries.
- **nil values in `:lib/options` remove wire keys.** Options still merge
  into the wire body last, but a nil value now deletes the key instead
  of sending JSON null — the escape hatch for adapter-injected defaults
  that some servers reject (e.g. `{:stream_options nil}` for
  OpenAI-compatible servers that predate `stream_options`). Adapter
  authors get the same behavior from `clj-llm.provider/merge-options`.
- **Provider configs carry `:lib/name`** (the name the provider was
  registered under), replacing the internal `:clj-llm.config/name` tag —
  provider config maps now contain only adapter-owned unqualified keys
  and `:lib/`-qualified library keys, as the keyspace rule says.
- **Dropped the `kirahowe.` prefix from all namespaces**: they are now
  `clj-llm.core`, `clj-llm.eval`, `clj-llm.provider`, ... (the Maven
  artifact remains `com.kirahowe/clj-llm`); the integrant key is
  `:clj-llm/config`.
- **Keyspace policy.** Every library-defined key in maps users author or
  store (config, requests, responses/records, cases, variants, suites,
  reports) is namespaced `:lib/...`; unqualified and user-namespaced
  keys in those maps are reserved for users forever. Protocol structures
  (messages, tool definitions, tool calls, usage, stream chunks, scorer
  results) keep plain spec'd keys whose plain keyspace is reserved. The
  `lib` prefix is deliberately short — it only distinguishes library keys
  from user keys inside this library's own maps and needs no global
  uniqueness; the Integrant key `:clj-llm/config` is the one exception
  because it lives in the user's shared system map.
- **HTTP moved to `java.net.http`** (JDK built-in) — clj-http and its
  Apache HttpClient dependency tree removed; the library's dependencies
  are now aero, cheshire and malli only.
- **JSON moved from charred to cheshire**, for babashka compatibility:
  charred's `deftype` over `java.util.Iterator` cannot load under SCI,
  which made `(require '[clj-llm.core])` fail under bb outright.
  cheshire is compiled into babashka natively, so the library now loads
  and runs under bb as well as the JVM. On the JVM cheshire brings
  Jackson along — the price of running everywhere.
- **Streaming chunks are type-tagged**: `:lib/on-chunk` receives
  `{:type :text :text delta}`. Callbacks must ignore unknown types —
  this is how future chunk kinds (tool-call deltas, thinking, ...)
  arrive without breaking existing code.
- **Adapter boundary split into SPI and API** (the Integrant
  `init-key`/`init` shape): adapters implement the `-`-prefixed SPI
  multimethods — `(-generate! provider-config request opts)`,
  `(-embed! ...)`, `(-start provider-config opts)`, `(-stop ...)` — each
  with exactly one frozen signature whose trailing `opts` map is
  reserved harness context (empty today); callers use the unprefixed
  functions (`generate!`, `embed!`, `start`, `stop`) where `opts` is
  optional. Future context always travels inside `request`/`opts`, never
  as new positional arguments. The full compatibility contract is
  documented in the `clj-llm.provider` docstring.
- **OpenAI adapter emits `max_completion_tokens`** (current protocol
  field) instead of the deprecated `max_tokens`; set
  `:legacy-max-tokens? true` on the provider for older OpenAI-compatible
  servers.
- Error `:type` values are now flat `:lib/...` keywords
  (`:lib/http-error`, `:lib/config-error`, ...), decoupled from
  internal namespace layout and frozen as public API.

### Added
- **`:lib/network-error`** for network-level failures (connect errors,
  timeouts, dropped streams), wrapping the underlying `IOException` —
  so both "the provider said no" (`:lib/http-error`) and "the provider
  never answered" are `ex-info`s with stable types.
- **Malli schemas for every public contract** (`clj-llm.spec`): messages,
  tools, requests, responses, config, cases, variants, suites. Requests,
  configs and suites are validated at the boundary with humanized errors
  (`:lib/invalid-request`, `:lib/invalid-config`,
  `:lib/invalid-suite`).
- **System-level evals**: a suite's `:lib/task` (function or qualified
  symbol) is what a case×variant runs — default is a single `generate`
  call; supply your own to eval a whole pipeline/agent/handler with the
  same cases, scorers and reports.
- **Score thresholds**: `:lib/thresholds {scorer-id min-mean}` adds
  `:lib/passed?` to reports and makes the CLI exit non-zero on
  regression — evals as a CI gate.
- **Report provenance**: reports carry `:lib/run-at`,
  `:lib/case-count`, `:lib/variant-count`, and each variant summary
  records the model that actually served it.
- Scorers (and `:lib/task`) in EDN suites may be qualified symbols,
  resolved with `requiring-resolve` at run time.
- Initial implementation: stateless `generate` and `embed` API over
  provider-agnostic config.
- Evals as a first-class concept: every response is a complete,
  replayable interaction record (`:lib/request`, `:lib/usage`,
  `:lib/latency-ms`, `:lib/started-at`, `:lib/op`), an
  `:lib/on-interaction` hook collects records from live traffic, and
  `clj-llm.eval` runs suites (cases × variants) with built-in scorers,
  custom scorers and model-graded `llm-judge` scoring into per-variant
  comparison summaries.
- EDN config files read with aero (`#env`, `#or`, `#profile`, ...).
- Adapters for Anthropic, the OpenAI chat-completions protocol (OpenAI,
  OpenRouter, Groq, Together, vLLM, LM Studio, ...) and Ollama's native API.
- Streaming via an `:lib/on-chunk` callback (SSE and NDJSON).
- Automatic tool-calling loop for tools defined as maps with a `:fn`.
- Model aliases (`:lib/models` in config) so code can name intents,
  not vendors.
- Optional Integrant bindings (`clj-llm.integrant`) with provider
  `start`/`stop` lifecycle hooks.
- Babashka tasks for all dev workflows (`bb tasks`), including `bb eval`.
- Documentation book under `notebooks/`, rendered with Clay + Quarto.

Planned work lives in the book's
[roadmap chapter](notebooks/roadmap.md) — everything there is additive
by design.

[Unreleased]: https://github.com/kirahowe/clj-llm/compare/v0.1.0-alpha1...HEAD
[0.1.0-alpha1]: https://github.com/kirahowe/clj-llm/releases/tag/v0.1.0-alpha1
