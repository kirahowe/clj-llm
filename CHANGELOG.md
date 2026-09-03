# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

This release adds portable structured generation, explicit execution
capabilities, and safer defaults at the tool and provider transport
boundaries.

### Added
- **All-or-none controls for automatic tool execution.** `generate`
  accepts `:llm/tool-policy` (`[tool-call tool-metadata]`) and
  `:llm/tool-argument-validator` (`[arguments tool-metadata]`); false/nil
  or an exception rejects the complete provider-returned batch before
  any tool function runs. `:llm/max-tool-calls` adds a total cross-round
  invocation budget, defaulting to 10 independently of the existing
  round limit. Unknown tools and insufficient remaining budget reject
  the batch too. Structured local `:llm/tool-rejections` report call
  identity and reasons without entering provider messages; policy,
  validator, and tool function values are scrubbed from provider-bound
  and replayable requests. Tools with no `:fn` retain the manual path.
  `:parameters` remains provider guidance, not runtime validation.
- **Bounded provider responses.** Every built-in adapter now inherits
  shared limits for non-streaming success/error bodies (8 MiB), individual
  SSE/NDJSON lines (1 MiB), and cumulative streams (32 MiB), plus a
  10-minute whole-stream and 60-second idle-read deadline. Provider configs
  can tune `:max-response-bytes`, `:max-stream-line-bytes`,
  `:max-stream-bytes`, `:stream-timeout-ms`, and
  `:stream-idle-timeout-ms`. Exact limits are accepted; overruns close the
  response and throw body-free `:llm/response-limit` data. Existing
  `:timeout-ms` still covers the complete non-streaming request and response.
- **Portable structured responses for `generate`.** Requests accept
  `:llm/response-format {:type :json-schema :name ... :schema ...}`;
  Anthropic, OpenAI-compatible, and Ollama adapters map it to their
  native wire formats without transforming the schema. Terminal text is
  preserved and decoded into `:llm/structured` (recursively keywordized,
  including a present nil for JSON `null`), while malformed output is
  retained as `:llm/structured-error` response data. Decoding is
  independent of finish reason and occurs only once a tool loop has a
  final answer.
- **Provider capability SPI.** `-supports?` / `supports?` adds
  model/request-aware feature reporting with a compatibility-safe false
  default. Provider `:llm/capabilities` booleans override the adapter,
  and unsupported structured requests fail before HTTP with exact
  `:llm/unsupported-capability` provenance.
- **OpenAI refusal normalization.** Streaming and non-streaming refusal
  text now becomes normalized assistant text with finish reason
  `:refusal`; streaming refusal deltas are emitted as ordinary text
  chunks and retained in the synthesized raw response.
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
- **Provider maps reject unknown `:llm`-qualified keys at resolution
  time.** Adapter options are unqualified, so a near-miss like
  `:llm/base-url` used to be silently ignored — the adapter fell back
  to its default endpoint, in the worst case sending prompts and
  credentials somewhere the config never pointed. The `:llm` namespace
  is reserved to the library, so such keys are always mistakes; they
  now throw `:llm/config-error` naming the keys and suggesting the
  unqualified spelling. Unqualified and foreign-namespace keys still
  flow through to adapters untouched.
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
- **Executable eval-suite symbols now require an explicit capability.**
  `eval/run` rejects qualified `:llm/task` and scorer symbols by default,
  before `requiring-resolve` or namespace loading, with typed
  `:llm/eval-code-not-allowed` data identifying the symbol, role, suite
  path, and `{:allow-code? true}` opt-in. Only literal boolean `true`
  grants the capability; false/nil stay safe and truthy non-booleans fail
  with `:llm/invalid-run-options` before suite-source reading. The eval
  CLI exposes the capability as `--allow-code`. Built-in keyword scorers,
  inert suites, and function values already supplied in an in-memory
  suite keep working without the option; executable code still runs
  unsandboxed in the current JVM after opt-in.
- **Security-sensitive defaults are stricter.** Tool exception details
  are redacted before the error is sent back to a provider, and provider
  HTTP redirects are refused so authentication headers cannot cross a
  redirect boundary. Tool documentation now treats model-supplied calls
  and arguments as untrusted input requiring application validation and
  authorization.
- **Built-in provider endpoints are preflighted before network I/O.**
  Generation, streaming, and embeddings now reject malformed, relative,
  non-HTTP(S), hostless, credential-bearing, query/fragment-bearing, and
  invalid-port destinations with safe typed local errors. Providers may
  supply an optional `:endpoint-policy` over normalized
  `{:scheme :host :port :path}` values; false/nil rejects and exceptions
  are wrapped without exposing URLs, headers, credentials, or prompts.
  Local/private HTTP remains supported. Provider configuration is
  documented as trusted application input, and the policy is explicitly
  not DNS-pinned IP/network-range enforcement; stronger deployments need
  egress controls or a custom transport/provider.
- **Jackson is pinned to 2.21.4** to avoid the vulnerable asynchronous
  parser code pulled transitively by Cheshire 6.2.0. clj-llm uses
  Cheshire's synchronous parsing path, but the fixed dependency keeps
  released applications and security scanners off the affected version.
- **The prompt-server example is local-only and body-limited.** It binds
  to `127.0.0.1` and rejects prompt bodies larger than 64 KiB.

### Fixed
- **Non-streaming request timeouts always surface as
  `HttpTimeoutException`.** The JDK's request-level timeout races the
  library's own deadline timer; when the JDK side aborted the exchange
  first, the failure leaked as a plain `IOException` ("closed")
  instead of the documented timeout cause. Any `IOException` arriving
  after the request deadline now converts to the timeout exception with
  the original preserved as its cause, so retry logic can rely on the
  cause type. Streaming requests are unaffected: their `:timeout-ms`
  only reaches through response headers, and stream deadlines already
  throw typed `:llm/response-limit` errors.

### Migration notes
- Commit `b3b8e4b` renamed the entire keyspace from `:lib/*` to
  `:llm/*` ahead of the alpha release; the 0.1.0-alpha1 notes below
  are written in the final `:llm/*` keyspace. A consumer pinned to an
  earlier git SHA migrates mechanically (find/replace `:lib/` →
  `:llm/`).
- Anything reading `:model` from a per-variant summary (or a stored
  report) now reads `:models`, a vector.
- Automatic tool loops now execute at most 10 tool calls per `generate`
  by default, across all rounds. Set `:llm/max-tool-calls` explicitly
  when an application intentionally needs a different total; 0 disables
  automatic invocation and returns local budget rejection records.
  Applications that previously validated or authorized only inside
  `:fn` can move those checks to `:llm/tool-argument-validator` and
  `:llm/tool-policy` so an invalid batch has no partial side effects.
- Eval callers that previously relied on qualified task/scorer symbols
  must add `:allow-code? true` to the third argument of `eval/run`, for
  example `(eval/run config suite {:allow-code? true})`. CLI invocations
  must add `--allow-code` before or among the existing
  `[suite.edn [llm.edn [profile]]]` positionals. Do not add the option
  for data-only suites or in-memory function values; those remain the
  safe/default and trusted application paths respectively.

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
