# Writing a provider adapter

The three built-in adapters cover Anthropic, everything that speaks the OpenAI chat-completions protocol, and Ollama's native API. If you need another wire protocol — a niche provider, an internal gateway, a test double — an adapter is a page of code: multimethod implementations dispatching on the `:clj-llm/adapter` key of a provider config map.

## The minimum viable adapter

```clojure
(ns my.app.adapters.acme
  (:require [clj-llm.provider :as provider]
            [clj-llm.http :as http]))

(defmethod provider/generate! :acme
  [provider-config request _opts]
  (let [{:keys [body]} (http/post-json
                        {:url (str (:base-url provider-config) "/complete")
                         :headers {"authorization" (str "Bearer " (:api-key provider-config))}
                         :timeout-ms (:timeout-ms provider-config)
                         :body {:model (:clj-llm/model request)
                                :messages (mapv (fn [{:keys [role content]}]
                                                  {:role (name role) :content content})
                                                (:clj-llm/messages request))}})]
    {:message {:role :assistant :content (:completion body)}
     :model (:model body)
     :usage {:input-tokens (:prompt_tokens body)
             :output-tokens (:completion_tokens body)}
     :finish-reason :stop
     :raw body}))
```

Register it in config like any built-in:

```clojure
#:clj-llm{:providers {:acme {:clj-llm/adapter :acme
                             :base-url "https://api.acme.example"
                             :api-key #env ACME_API_KEY}}}
```

That's genuinely all: `generate` resolves the provider, applies defaults, runs the tool loop, builds the interaction record — your adapter only translates one normalized request into one wire call and one normalized result back.

## The contract

Your `generate!` receives three arguments — the raw provider config (so your own keys like `:api-key` flow through untouched), the normalized request, and a reserved `opts` map (empty today; accept and ignore it):

```clojure
#:clj-llm{:model       "model-id"         ; already resolved to a string
          :messages    [{:role :user :content "..."} ...]
          :system      "..."              ; optional
          :max-tokens  4096               ; optional
          :temperature 0.7                ; optional
          :tools       [{:name ... :description ... :parameters ... :fn ...}]
          :on-chunk    (fn [{:keys [type text]}] ...)  ; optional; emit {:type :text :text delta}
          :options     {...}}             ; provider-specific passthrough — merge into your wire body last
```

And returns:

```clojure
{:message       {:role :assistant :content "..."}   ; + :tool-calls [{:id :name :arguments}] if any
 :model         "model-id-as-reported"
 :usage         {:input-tokens n :output-tokens n}
 :finish-reason :stop                                ; :length | :tool-calls | :refusal | <other kw>
 :raw           <parsed wire response>}
```

Conventions the built-ins follow, worth copying:

- **Honor `:clj-llm/options` by merging it into the wire body last** — it's the user's escape hatch for anything your adapter doesn't model.
- **Streaming**: when `:clj-llm/on-chunk` is present, call it with `{:type :text :text delta}` per text delta and still return the complete result. `clj-llm.http/post-json-lines` reduces over response lines (SSE and NDJSON both), and `clj-llm.http/sse-data` extracts SSE data payloads.
- **Errors**: let `clj-llm.http`'s `:clj-llm/http-error` propagate; throw `ex-info` with `{:type :clj-llm/missing-api-key}` for configuration problems you detect yourself.
- **Structure for testability**: keep pure `build-request` / `parse-response` functions separate from the multimethod, so your adapter tests need no HTTP at all. See `clj-llm.providers.ollama` for the compact reference implementation, and `book.demo` in this book's source for a no-HTTP test double.
- **Embeddings, lifecycle**: implement `embed!` if the provider has embeddings; implement `start`/`stop` (each `[provider-config opts]`) only if your adapter needs real state like OAuth token refresh — the integrant bindings call them on system start/halt.

## What clj-llm promises your adapter

The compatibility rules (also in the `clj-llm.provider` docstring): new request keys are additive and ignorable; new result keys are always optional; the multimethod signatures are frozen — anything new travels inside `request` or `opts`, never as a new positional argument; and any future multimethod ships with a `:default`, so your adapter never has to change just to keep loading. An adapter written today is an adapter that works in every future version.
