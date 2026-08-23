# Design and compatibility

This chapter documents the compatibility rules clj-llm intends to guarantee from `0.1.0` onward. During the alpha, these rules may still change when testing finds a problem.

## Which keys belong to clj-llm

Config, request, response, eval case, variant, suite, and report maps use namespaced `:llm/...` keys for library data. Other keys in those maps belong to your application. For example, an eval case may include `:support/article-id`, and a provider config may include adapter-specific keys such as `:api-key` or `:base-url`.

The Integrant key is `:clj-llm/config` because it lives in a system map shared with other libraries.

Nested protocol data uses shorter, unqualified keys:

- messages use `:role`, `:content`, `:tool-calls`, `:tool-call-id`, and `:name`;
- tool definitions use `:name`, `:description`, `:parameters`, and `:fn`;
- tool calls use `:id`, `:name`, and `:arguments`;
- usage uses keys such as `:input-tokens` and `:output-tokens`;
- stream chunks use `:type` and `:text`;
- scorer results use `:score`, `:reasoning`, and `:error`.

Those unqualified keys are reserved inside these nested structures. Add your own data with namespaced keys. Do not add application keys in the `:llm` namespace.

## Schemas and validation

`clj-llm.spec` contains [malli](https://github.com/metosin/malli) schemas for the public data shapes. Configs, generation requests, embedding requests, and eval suites are validated when they enter the public API. Invalid values throw `ex-info` with a readable `:explain` value.

The map schemas are open, so application keys are accepted. Responses have a schema for documentation and tooling, but clj-llm constructs them rather than validating them at runtime.

## Stored conversations and requests

A message's `:content` is a string today. The schema also reserves a vector of maps with a `:type` key for future content such as images or audio. Current adapters do not promise support for those content parts yet.

Use `:llm/text` when you only need the generated text. This avoids depending on provider-specific response data or future message content types.

Response records omit `:llm/on-chunk`, `:llm/on-interaction`, and tool `:fn` values from the stored `:llm/request`. Reattach tool functions before replaying a tool-using request.

## Streaming

Every `:llm/on-chunk` value has a `:type`. Text chunks currently look like `{:type :text :text "delta"}`. Callbacks should ignore chunk types they do not handle, because later releases may add types for tool calls or other streamed data.

## Provider adapter API

Adapters implement the `-`-prefixed multimethods in `clj-llm.provider`. Application and library code call their unprefixed wrappers:

| Adapter implements | Caller uses |
|---|---|
| `-generate!` | `generate!` |
| `-embed!` | `embed!` |
| `-supports?` | `supports?` |
| `-start` | `start` |
| `-stop` | `stop` |

The adapter signatures are fixed:

```clojure
(-generate! provider-config request opts)
(-embed! provider-config request opts)
(-supports? provider-config capability opts)
(-start provider-config opts)
(-stop provider-config opts)
```

New request keys may be ignored by adapters. New result keys are optional. New context will be added inside `request` or `opts`, not as another positional argument. Any new adapter multimethod will have a default implementation so existing adapters continue to load.

Unqualified provider config keys belong to the adapter. clj-llm adds `:llm/name` when it resolves a configured provider so errors can identify that provider.

Multimethods are used because provider configs remain maps and dispatch on the value of `:llm/adapter`. A Clojure protocol would dispatch on the map's type instead.

## Errors

Errors thrown by clj-llm use `ex-info` with a `:type` in `ex-data`:

- `:llm/http-error` means the provider returned an unsuccessful HTTP response. Its data includes `:status`, `:url`, and parsed `:body`.
- `:llm/network-error` means no complete response arrived because of a connection error, timeout, or dropped stream. Its data includes `:url` and wraps the underlying `IOException`.
- Input and config errors use `:llm/invalid-request`, `:llm/invalid-config`, `:llm/invalid-suite`, `:llm/config-error`, `:llm/config-not-found`, or `:llm/invalid-case`.
- Lookup and feature errors use `:llm/unknown-adapter`, `:llm/unknown-scorer`, `:llm/missing-api-key`, `:llm/unsupported`, or `:llm/unsupported-capability`.
- A provider stream event may use `:llm/stream-error`.

An unsupported structured response fails before the adapter makes an HTTP call. Its data includes `:provider`, `:adapter`, `:model`, and `:capability`, and no interaction record is created.

Malformed JSON from a completed structured request is different: the provider call happened, so the response is returned with `:llm/structured-error` and the original `:llm/text`. It does not throw.

Finish reasons are an open set. Common provider values are normalized to `:stop`, `:length`, `:tool-calls`, and `:refusal`; unknown values remain keywords.

## Structured responses

The portable request shape is `:llm/response-format {:type :json-schema :name ... :schema ...}`. Built-in adapters translate the wrapper but pass the schema itself through unchanged. Each provider and model may support a different JSON Schema subset; a provider rejection is returned as `:llm/http-error`.

For a final answer, clj-llm parses the complete text and keywordizes JSON object keys. The response contains either `:llm/structured` or `:llm/structured-error`. A successful JSON `null` is represented by a present `:llm/structured` key with a nil value. clj-llm does not validate the decoded value against the requested schema.

Pending tool calls have neither structured key. An automatic tool loop sends the response format on every round and parses only the final answer.

The built-in adapters report support for `:json-schema-response`; custom adapters default to false. A provider may override this with `:llm/capabilities {:json-schema-response true}` or `false`. `:llm/options` is still applied last to the provider request, even if it replaces or removes the adapter's structured-output field.

## Other design choices

- Providers represent configured accounts or endpoints. Adapters implement protocols such as Anthropic Messages, OpenAI Chat Completions, or Ollama's native API. Several providers may use the same adapter.
- `generate` is synchronous and stateless. A conversation is a messages vector supplied with the request.
- The direct runtime dependencies are aero, cheshire, and malli. HTTP uses the JDK's `java.net.http` client.
- Evals ship in the main library. A custom `:llm/task` can evaluate an application function that makes one or more model calls.
- The OpenAI adapter sends `max_completion_tokens`. Set `:legacy-max-tokens? true` on a provider that requires the older `max_tokens` field.
- `:llm/options` is applied to the provider request last. A nil option removes that field.
