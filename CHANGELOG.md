# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

Everything here comes from migrating the library's first real consumer —
a custom `:llm/task` suite wrapping a two-call pipeline with structured
results — onto the eval layer. Each entry closes a place where that
layer still assumed a case is exactly one chat request.

### Added
- **Eval runs observe every LLM call a task makes.** The runner hands
  each task a config whose `:llm/on-interaction` collects interaction
  records (chaining any hook the config already had). Each result
  carries the records as `:llm/interactions`, and scorers receive them
  as `:interactions` in their context map. Multi-call tasks no longer
  go dark in the report: there is no longer any need to return a real
  generate response just to keep the summary numbers honest.
- **`llm-judge` accepts `:prompt-fn`** — a function of
  `{:criteria :config :case :variant :response}` returning the judge's
  user prompt. The default, `judge-prompt` (now public, so a custom
  `:prompt-fn` can wrap it), keeps today's layout: `:llm/input`,
  `:llm/expected`, and the response's `:llm/text` — and *only* those,
  which is why structured tasks and domain-heavy cases should supply
  `:prompt-fn` rather than let the judge grade without seeing the
  domain context.
- **`variant->request`** — the variant minus `:llm/id`, for custom
  tasks to merge into each generate call they make. A variant key the
  task never forwards changes nothing (the run would compare identical
  code under two labels), so the task docs now say loudly: honor the
  variant, and this helper makes it one merge.

### Changed
- **Cases need `:llm/input`/`:llm/messages` only under the default
  task.** A suite with a custom `:llm/task` may write cases as pure
  domain data — your own keys plus optional `:llm/expected` and
  `:llm/id` — with no display strings invented to appease validation.
  Suites without `:llm/task` are validated exactly as before.
- **Per-variant summaries aggregate over collected interaction
  records** instead of reading keys off the task's return value:
  `:model` becomes `:models` (every model that actually served the
  variant), `:calls` counts the LLM calls made, latency is per call,
  and usage sums every call the task made. When a run collected
  nothing (e.g. a task built its response outside the run's sight),
  the returned response still serves as the record, as before.
  `print-summary` gains a `calls` column.

### Migration notes
- Commit `b3b8e4b` renamed the entire keyspace from `:lib/*` to
  `:llm/*` ahead of the alpha release; the 0.1.0-alpha1 notes below
  are written in the final `:llm/*` keyspace. A consumer pinned to an
  earlier git SHA migrates mechanically (find/replace `:lib/` →
  `:llm/`).
- Anything reading `:model` from a per-variant summary (or a stored
  report) now reads `:models`, a vector.

## [0.1.0-alpha1] — 2026-08-03

Initial public alpha. The API is intended to be final; the alpha window
exists so anything that would force a breaking change can still surface
and be fixed before 0.1.0.

### Changed (pre-release API finalization)
- **`:llm/prompt` appends to `:llm/messages`** as the next user message
  (and is plain zero-shot shorthand when there are no messages).
  Previously, passing both silently dropped the prompt — a request that
  looked like "continue the conversation with this question" answered
  the bare history instead.
- **`:llm/system` wins over inline system messages in every adapter.**
  The OpenAI and Ollama adapters used to silently ignore `:llm/system`
  whenever the messages already contained a `:system`-role message;
  Anthropic did the opposite. One rule now: an explicit `:llm/system`
  replaces whatever system messages the conversation carries.
- **nil values in `:llm/options` remove wire keys.** Options still merge
  into the wire body last, but a nil value now deletes the key instead
  of sending JSON null — the escape hatch for adapter-injected defaults
  that some servers reject (e.g. `{:stream_options nil}` for
  OpenAI-compatible servers that predate `stream_options`). Adapter
  authors get the same behavior from `clj-llm.provider/merge-options`.
- **Provider configs carry `:llm/name`** (the name the provider was
  registered under), replacing the internal `:clj-llm.config/name` tag —
  provider config maps now contain only adapter-owned unqualified keys
  and `:llm/`-qualified library keys, as the keyspace rule says.
- **Dropped the `kirahowe.` prefix from all namespaces**: they are now
  `clj-llm.core`, `clj-llm.eval`, `clj-llm.provider`, ... (the Maven
  artifact remains `com.kirahowe/clj-llm`); the integrant key is
  `:clj-llm/config`.
- **Keyspace policy.** Every library-defined key in maps users author or
  store (config, requests, responses/records, cases, variants, suites,
  reports) is namespaced `:llm/...`; unqualified and user-namespaced
  keys in those maps are reserved for users forever. Protocol structures
  (messages, tool definitions, tool calls, usage, stream chunks, scorer
  results) keep plain spec'd keys whose plain keyspace is reserved. The
  `llm` prefix is deliberately short and names the library — it only
  distinguishes library keys from user keys inside this library's own
  maps and needs no global uniqueness; the Integrant key
  `:clj-llm/config` spells the name out in full because it lives in the
  user's shared system map.
- **HTTP moved to `java.net.http`** (JDK built-in) — clj-http and its
  Apache HttpClient dependency tree removed; the library's dependencies
  are now aero, cheshire and malli only.
- **JSON moved from charred to cheshire**, for babashka compatibility:
  charred's `deftype` over `java.util.Iterator` cannot load under SCI,
  which made `(require '[clj-llm.core])` fail under bb outright.
  cheshire is compiled into babashka natively, so the library now loads
  and runs under bb as well as the JVM. On the JVM cheshire brings
  Jackson along — the price of running everywhere.
- **Streaming chunks are type-tagged**: `:llm/on-chunk` receives
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
- Error `:type` values are now flat `:llm/...` keywords
  (`:llm/http-error`, `:llm/config-error`, ...), decoupled from
  internal namespace layout and frozen as public API.

### Added
- **`:llm/network-error`** for network-level failures (connect errors,
  timeouts, dropped streams), wrapping the underlying `IOException` —
  so both "the provider said no" (`:llm/http-error`) and "the provider
  never answered" are `ex-info`s with stable types.
- **Malli schemas for every public contract** (`clj-llm.spec`): messages,
  tools, requests, responses, config, cases, variants, suites. Requests,
  configs and suites are validated at the boundary with humanized errors
  (`:llm/invalid-request`, `:llm/invalid-config`,
  `:llm/invalid-suite`).
- **System-level evals**: a suite's `:llm/task` (function or qualified
  symbol) is what a case×variant runs — default is a single `generate`
  call; supply your own to eval a whole pipeline/agent/handler with the
  same cases, scorers and reports.
- **Score thresholds**: `:llm/thresholds {scorer-id min-mean}` adds
  `:llm/passed?` to reports and makes the CLI exit non-zero on
  regression — evals as a CI gate.
- **Report provenance**: reports carry `:llm/run-at`,
  `:llm/case-count`, `:llm/variant-count`, and each variant summary
  records the model that actually served it.
- Scorers (and `:llm/task`) in EDN suites may be qualified symbols,
  resolved with `requiring-resolve` at run time.
- Initial implementation: stateless `generate` and `embed` API over
  provider-agnostic config.
- Evals as a first-class concept: every response is a complete,
  replayable interaction record (`:llm/request`, `:llm/usage`,
  `:llm/latency-ms`, `:llm/started-at`, `:llm/op`), an
  `:llm/on-interaction` hook collects records from live traffic, and
  `clj-llm.eval` runs suites (cases × variants) with built-in scorers,
  custom scorers and model-graded `llm-judge` scoring into per-variant
  comparison summaries.
- EDN config files read with aero (`#env`, `#or`, `#profile`, ...).
- Adapters for Anthropic, the OpenAI chat-completions protocol (OpenAI,
  OpenRouter, Groq, Together, vLLM, LM Studio, ...) and Ollama's native API.
- Streaming via an `:llm/on-chunk` callback (SSE and NDJSON).
- Automatic tool-calling loop for tools defined as maps with a `:fn`.
- Model aliases (`:llm/models` in config) so code can name intents,
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
