# Design and compatibility

clj-llm intends to commit to backwards compatibility permanently: code written against version 0.1 should run, unchanged, against every future version, and data stored by 0.1 (conversations, interaction records, eval reports) should stay readable forever. That is only possible if the contracts are explicit about where they can grow. This chapter is those contracts.

## The keyspace rule

Every map in the API belongs to one of two zones.

**Boundary maps** are the maps you author, store, or extend: config, requests, responses/records, eval cases, variants, suites, reports. In these maps, every key the library defines is namespaced `:lib/...`, and everything else (unqualified keys, or keys in your own namespaces) is yours. The library will never assign meaning to a non-`:lib/` key in a boundary map. This is what lets a case carry your custom fields for your custom scorers, a response be decorated with your bookkeeping before storage, and a provider map hold adapter-specific settings, with no risk that a future release collides with them.

Why `lib` rather than something globally unique: the prefix only ever has to distinguish library keys from *your* keys inside maps this library defines. It never needs to be unique across the ecosystem, so the shortest unambiguous marker wins. The one deliberate exception is the Integrant key `:clj-llm/config`, because an Integrant system map is shared territory where many libraries' keys live side by side and a key must say which library it belongs to.

**Protocol structures** are the shapes the library defines end-to-end and that benefit from staying industry-familiar: messages (`:role`, `:content`, `:tool-calls`, `:tool-call-id`, `:name`), tool definitions (`:name`, `:description`, `:parameters`, `:fn`), tool calls (`:id`, `:name`, `:arguments`), usage (`:input-tokens`, `:output-tokens`, ...), stream chunks (`:type`, `:text`), and scorer results (`:score`, `:reasoning`, `:error`). These keep plain keys, and the *plain* keyspace inside them is reserved: if you extend a message or a scorer result, use your own namespaced keys.

The corollary, and it's a commitment too: **don't invent keys in the `:lib/` namespace.** Validation may tighten around unknown `:lib/` keys in any release; that reserved space is what makes every other promise keepable.

## Schemas are the contract

Every structure above has a [malli](https://github.com/metosin/malli) schema in `clj-llm.spec`, the machine-checkable version of this chapter. Requests, configs and suites are validated at the API boundary, and violations throw `ex-info` with a humanized `:explain`. All map schemas are open: extra keys always validate, in keeping with the keyspace rule.

## Stored data stays readable

Messages are the contract with the longest lifetime, because users are told to persist them (in sessions, databases, logs) and to feed collected interaction records back in as eval cases. So the message spec is frozen with its growth path already reserved: `:content` is a string today, and a vector of typed content-part maps (each with a `:type`) is reserved for multimodal content. When images or audio arrive, they arrive as new part types inside that vector. Old stored conversations remain valid, and code that reads `:lib/text` on responses (rather than digging into message internals) keeps working without edits.

## Streaming grows by chunk type

`:lib/on-chunk` payloads always carry `:type`. Today the only type is `:text`, shaped `{:type :text :text "delta"}`. Future capabilities (tool-call deltas, thinking/reasoning streams, round boundaries in the tool loop) will arrive as new `:type` values, never by changing the shape of an existing one. The contract on your side: ignore chunks whose type you don't recognize. A callback written that way today never breaks.

## The adapter contract is frozen

Third-party adapters are a compatibility surface in both directions, so `clj-llm.provider` splits into an SPI and an API, in the style of Integrant's `init-key`/`init`: the `-`-prefixed multimethods (`-generate!`, `-embed!`, `-start`, `-stop`) are what adapters *implement*, and the unprefixed functions (`generate!`, `embed!`, `start`, `stop`) are what callers *call*, where the trailing `opts` map is optional. The library commits to:

- New request keys are additive; adapters may ignore what they don't understand.
- New result keys are always optional; `:message`, `:usage`, `:finish-reason` and `:raw` remain sufficient.
- SPI signatures are frozen: `(-generate! provider-config request opts)`, `(-embed! provider-config request opts)`, `(-start provider-config opts)`, `(-stop provider-config opts)`. Each has exactly one arity, so an adapter implements exactly one thing and there is no forgettable delegating boilerplate. The `opts` map is reserved harness context (empty today: cancellation, deadlines and telemetry are the kinds of things that will travel there). Nothing will ever arrive as a new positional argument, because multimethod arity changes are the one thing existing adapters could never survive. The unprefixed API functions belong to the library and may grow conveniences freely. They are also the permanent seam for future validation or instrumentation around adapter calls.
- Any future SPI multimethod ships with a `:default` implementation, so existing adapters keep loading without edits.
- In provider config maps, only `:lib/`-qualified keys are the library's; the unqualified keyspace belongs to the adapter named by `:lib/adapter`.

(Why multimethods and not a protocol: protocols dispatch on the *type* of the first argument, and provider configs are plain maps on purpose. A protocol would force adapters to become instantiated objects behind a constructor registry, giving up config-as-data. Multimethods dispatch on a value in the data, which is the shape of this problem; the ergonomic arity story lives in the wrapper functions instead.)

## Errors are part of the API

Thrown `ex-info`s carry a `:type` in their ex-data, and these keywords are stable, flat (decoupled from internal namespace layout), and never change meaning:

`:lib/http-error` (with `:status`, `:url`, `:body`), `:lib/invalid-request`, `:lib/invalid-config`, `:lib/invalid-suite`, `:lib/config-error`, `:lib/config-not-found`, `:lib/unknown-adapter`, `:lib/unknown-scorer`, `:lib/invalid-case`, `:lib/missing-api-key`, `:lib/unsupported`, `:lib/stream-error`.

`:lib/finish-reason` values are an open set: the common ones are normalized (`:stop`, `:length`, `:tool-calls`, `:refusal`), and unrecognized provider reasons pass through as keywords rather than being erased.

## Architectural decisions, briefly

**Providers are accounts; adapters are protocols.** Two config entries can share an adapter (OpenAI and Groq both speak chat-completions), which is how three adapters cover effectively the whole ecosystem. Adding a provider is config; adding a protocol is a page of multimethods.

**Stateless by construction.** No client objects or connection state means the "integration story" for any framework is: pass the config map. The optional integrant bindings exist for lifecycle symmetry (and the `start`/`stop` hooks for hypothetical stateful adapters), not because the library needs them.

**The verb is `generate`, not `chat`.** A zero-shot completion isn't a conversation; a conversation is `generate` over accumulated messages. One verb, one mental model, and the eval system gets to treat every interaction uniformly.

**`java.net.http`, cheshire, aero, malli, and nothing else.** For a library, transitive dependencies are a tax on every consumer. The JDK's HTTP client does everything needed (including streaming); cheshire is babashka's native JSON codec (a thin Jackson wrapper on the JVM, zero-cost under bb); aero is tiny; malli is the one deliberate splurge because schemas *are* the compatibility strategy.

**Evals live in the core, with an extraction seam.** Keeping `clj-llm.eval` in the main artifact is a statement: measurement is not an optional extra. The `:lib/task` indirection doubles as the seam, because the eval harness runs arbitrary task functions and only *defaults* to `clj-llm.core/generate`. If the harness ever deserves a standalone life, it can move without breaking a caller.

**Wire compatibility tracks the present, with escape hatches.** The OpenAI adapter sends `max_completion_tokens` (the current field); `:legacy-max-tokens? true` on a provider covers older compatible servers. Anything the normalized request doesn't model can be forced onto the wire via `:lib/options`, which merges into the request body last.
