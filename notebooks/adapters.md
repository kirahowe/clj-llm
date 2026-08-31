# Writing a provider adapter

The built-in adapters cover Anthropic, OpenAI's Chat Completions API and compatible services, and Ollama's native API. Add an adapter when you need another provider API, an internal gateway, or a test double. Adapters are multimethod implementations selected by `:llm/adapter` in provider config.

Provider maps, base URLs, headers, credentials, and endpoint policies are trusted application configuration. Never derive them from request, tenant, upload, or other untrusted data: an adapter sends prompts and credentials to the configured destination.

## The minimum viable adapter

```clojure
(ns my.app.adapters.acme
  (:require [clj-llm.provider :as provider]
            [clj-llm.http :as http]))

(defmethod provider/-generate! :acme
  [provider-config request _opts]
  (let [{:keys [body]} (http/post-json
                        (merge
                         (http/request-options provider-config)
                         {:url (str (:base-url provider-config) "/complete")
                          :headers {"authorization" (str "Bearer " (:api-key provider-config))}
                          :body {:model (:llm/model request)
                                 :messages (mapv (fn [{:keys [role content]}]
                                                   {:role (name role) :content content})
                                                 (:llm/messages request))}}))]
    {:message {:role :assistant :content (:completion body)}
     :model (:model body)
     :usage {:input-tokens (:prompt_tokens body)
             :output-tokens (:completion_tokens body)}
     :finish-reason :stop
     :raw body}))
```

Require the adapter namespace when your application starts, then register its keyword in config:

```clojure
(require 'my.app.adapters.acme)
```

```clojure
#:llm{:providers {:acme {:llm/adapter :acme
                         :base-url "https://api.acme.example"
                         :api-key #env ACME_API_KEY}}}
```

`generate` resolves the provider and model, applies defaults, runs the tool loop, and builds the final response record. The adapter translates one normalized request into one provider call and normalizes the result.

## The contract

Implement the `-`-prefixed multimethods in `clj-llm.provider`. Callers use the unprefixed wrappers such as `provider/generate!`, whose final `opts` argument is optional. `-generate!` receives the configured provider map with an added `:llm/name`, the normalized request, and an `opts` map. Accept `opts` even when your adapter does not use it:

```clojure
#:llm{:model       "model-id"         ; already resolved to a string
      :messages    [{:role :user :content "..."} ...]
      :system      "..."              ; optional
      :max-tokens  4096               ; optional
      :temperature 0.7                ; optional
      :tools       [{:name ... :description ... :parameters ...}]
      :response-format {:type :json-schema :name "name" :schema {...}} ; optional
      :on-chunk    (fn [{:keys [type text]}] ...)  ; optional; emit {:type :text :text delta}
      :options     {...}}             ; provider-specific passthrough; merge into your wire body last
```

Core removes executable tool functions, policies, and argument validators before
the request crosses the adapter boundary. An adapter describes tools to the
provider; it never executes them. It may ignore any other request key it does
not understand.

And returns:

```clojure
{:message       {:role :assistant :content "..."}   ; + :tool-calls [{:id :name :arguments}] if any
 :model         "model-id-as-reported"
 :usage         {:input-tokens n :output-tokens n}
 :finish-reason :stop                                ; :length | :tool-calls | :refusal | <other kw>
 :raw           <parsed wire response>}
```

Follow these rules:

- **Apply `:llm/options` to the provider request last** with `provider/merge-options`. Non-nil values override fields and nil values remove them.
- **Declare structured response support** by implementing `(provider/-supports? provider-config :json-schema-response opts)` and returning true. The `opts` map contains `{:request normalized-request}`, so support may depend on the request or model. The default is false, and provider config under `:llm/capabilities` overrides the adapter. Translate the response-format wrapper without changing its `:schema`; core parses the final JSON. Apply `:llm/options` after adding the provider's structured-output field.
- **Streaming**: when `:llm/on-chunk` is present, call it with `{:type :text :text delta}` per text delta and still return the complete result. `clj-llm.http/post-json-lines` reduces over response lines (SSE and NDJSON both), and `clj-llm.http/sse-data` extracts SSE data payloads.
- **Endpoint preflight**: built-in requests require an absolute `http` or `https` URI with a host, no user-info, query, or fragment, and a valid port. `http/request-options` forwards the optional provider `:endpoint-policy` consistently. Before the shared transport constructs a JDK request or performs network I/O, the policy receives an immutable `{:scheme :host :port :path}` map: lower-case scheme/host, effective port, normalized raw path, and IPv6 host without brackets. False/nil throws `:llm/endpoint-rejected`; exceptions become `:llm/endpoint-policy-error`; invalid destinations use `:llm/invalid-endpoint` plus a safe `:reason`. Explicit localhost/private HTTP remains supported.
- **Policy limits**: endpoint policy performs no DNS resolution or address pinning, so use it for destination allowlists, not IP/network-range enforcement. DNS rebinding remains possible for a permitted hostname. Stronger guarantees require network egress controls or a custom transport/provider that connects to the checked address.
- **Errors**: let `clj-llm.http`'s typed HTTP, network, endpoint, and policy errors propagate; throw `ex-info` with `{:type :llm/missing-api-key}` for configuration problems you detect yourself. Endpoint preflight error data never includes the URL, headers, credentials, or request body. The provider config carries `:llm/name` (the name it was registered under), which makes error messages point at the right config entry.
- **Keep request building and response parsing separate from HTTP** so they can be tested without a server. `clj-llm.providers.ollama` is the shortest built-in example.
- **Embeddings, lifecycle**: implement `-embed!` if the provider has embeddings; implement `-start`/`-stop` (each `[provider-config opts]`) only if your adapter needs real state like OAuth token refresh. The integrant bindings call them on system start/halt.

## What clj-llm promises your adapter

From `0.1.0` onward, new request keys will be optional for adapters, new result keys will be optional for callers, and the adapter method signatures will not gain positional arguments. New context will travel inside `request` or `opts`. Any new adapter multimethod will have a default implementation. `-supports?` already follows that rule and defaults to false.
