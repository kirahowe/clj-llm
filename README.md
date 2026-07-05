# clj-llm

A small, functional Clojure library for calling large language models —
any model, from any provider, including local ones.

Inspired by [RubyLLM](https://rubyllm.com), rebuilt on Clojure values:

- **Stateless and functional.** No client objects, no global mutable
  state, no sessions. Every function takes a config map and returns data.
- **Context agnostic.** Because nothing is stateful, the same calls work
  in a web app, a CLI, a background job, or a one-off REPL experiment.
- **Config lives in config files.** API keys, base URLs, model choices —
  all of it comes from EDN config (with `#env` for secrets), never from
  source code.
- **Conversations are data.** A conversation is a vector of message
  maps. "Multi-turn" just means passing the previous `:messages` back
  in — no chat-object ceremony, and a zero-shot question is not
  pretending to be a chat.
- **One protocol away from any provider.** Adapters are multimethods;
  the OpenAI-compatible adapter alone covers most of the hosted and
  self-hosted ecosystem, and adding a new adapter is a page of code.

## Installation

Not yet on Clojars. Use it as a git dependency:

```clojure
;; deps.edn
{:deps {io.github.kirahowe/clj-llm {:git/url "https://github.com/kirahowe/clj-llm"
                                    :git/sha "..."}}}
```

## Configuration

Create an EDN config file (conventionally `llm.edn`, kept out of version
control if you like, though with `#env` there are no secrets in it). A
full example ships at
[`resources/clj-llm/config.example.edn`](resources/clj-llm/config.example.edn):

```clojure
{:providers
 {:anthropic {:adapter :anthropic
              :api-key #env "ANTHROPIC_API_KEY"}

  ;; the :openai adapter speaks the OpenAI chat-completions protocol,
  ;; so it covers OpenAI, OpenRouter, Groq, Together, vLLM, LM Studio...
  :groq {:adapter :openai
         :base-url "https://api.groq.com/openai/v1"
         :api-key #env "GROQ_API_KEY"}

  ;; local models through Ollama's native API
  :local {:adapter :ollama
          :base-url #or [#env "OLLAMA_HOST" "http://localhost:11434"]}}

 ;; aliases: code names an intent (:smart, :fast); config decides what
 ;; that means. Swap providers without touching code.
 :models
 {:smart {:provider :anthropic :model "claude-sonnet-4-6"}
  :fast  {:provider :groq :model "llama-3.3-70b-versatile"}}

 :defaults
 {:model :smart
  :max-tokens #profile {:dev 1024 :default 4096}}}
```

Three reader tags keep the file declarative:

| Tag        | Meaning                                             |
|------------|-----------------------------------------------------|
| `#env`     | value of an environment variable (`nil` if unset)   |
| `#or`      | first non-nil value in a vector                     |
| `#profile` | pick a branch by profile, e.g. `{:dev … :default …}`|

Load it with:

```clojure
(require '[kirahowe.clj-llm :as llm])

(def config (llm/read-config "llm.edn"))                  ; or any io/reader-able source
(def config (llm/read-config "llm.edn" {:profile :dev}))  ; select #profile branches
```

The config is a plain map — if you'd rather build it with
[aero](https://github.com/juxt/aero), your own loader, or literal EDN in
a test, everything downstream accepts it just the same.

## Usage

### Generating text

```clojure
;; zero-shot: a prompt in, a response map out
(llm/generate config "Why is the sky blue?")
;; => {:text "Sunlight scattering..."
;;     :messages [{:role :user :content "Why is the sky blue?"}
;;                {:role :assistant :content "Sunlight scattering..."}]
;;     :model "claude-sonnet-4-6"
;;     :provider :anthropic
;;     :usage {:input-tokens 13 :output-tokens 42}
;;     :finish-reason :stop
;;     :raw {...}}

;; pick a model per call — by alias, "provider/model" string, or map
(llm/generate config "Say hi." {:model :fast})
(llm/generate config "Say hi." {:model "local/llama3.2"})
(llm/generate config "Say hi." {:model {:provider :groq :model "qwen-2.5-72b"}})

;; everything else is a request key
(llm/generate config {:system "You are terse."
                      :prompt "Explain monads."
                      :max-tokens 200
                      :temperature 0.2})
```

### Multi-turn conversations

A conversation is the `:messages` vector. Continue one by conj-ing the
next user message onto the previous response's `:messages`:

```clojure
(def r1 (llm/generate config "Name a prime number between 100 and 200."))

(llm/generate config
              {:messages (conj (:messages r1)
                               {:role :user :content "Why is it prime?"})})
```

Store that vector wherever your context keeps state — a Ring session, an
atom, a database row. The library doesn't care.

### Streaming

Pass an `:on-chunk` callback; it receives `{:text "delta"}` for each
piece of output. The complete response map is still returned at the end.

```clojure
(llm/generate config "Tell me a story."
              {:on-chunk (fn [{:keys [text]}] (print text) (flush))})
```

### Tools (function calling)

Tools are maps. If every tool the model calls has a `:fn`, clj-llm runs
the call, feeds the result back, and loops (bounded by
`:max-tool-rounds`, default 10) until the model answers:

```clojure
(llm/generate config "What's the weather in Berlin?"
              {:tools [{:name "get-weather"
                        :description "Look up current weather for a city"
                        :parameters {:type "object"
                                     :properties {:city {:type "string"}}
                                     :required ["city"]}
                        :fn (fn [{:keys [city]}]
                              (fetch-weather city))}]})
```

`:parameters` is a JSON-schema map; `:fn` gets the parsed arguments
(keyword keys) and may return a string or any JSON-encodable value.
Exceptions are caught and reported back to the model as tool errors.

Omit `:fn` and the loop stays out of your way: the response comes back
with `:tool-calls` and `:finish-reason :tool-calls`, and you append
`{:role :tool :tool-call-id id :content result}` messages yourself —
useful when tool execution needs approval, queueing, or your own loop.

### Embeddings

```clojure
(llm/embed config "some text")            ; => {:embedding [0.01 ...] ...}
(llm/embed config ["chunk 1" "chunk 2"])  ; => {:embeddings [[...] [...]] ...}
```

Uses the `:embedding-model` alias from `:defaults`; override per call
with `{:model ...}`.

### Adding a provider adapter

An adapter is a couple of multimethod implementations, dispatching on
the `:adapter` key of a provider config:

```clojure
(require '[kirahowe.clj-llm.provider :as provider])

(defmethod provider/generate! :my-adapter
  [provider-config request]
  ;; take the normalized request, speak your wire protocol,
  ;; return {:message {...} :usage {...} :finish-reason ... :raw ...}
  ...)
```

See the `kirahowe.clj-llm.provider` docstring for the full contract, and
`kirahowe.clj-llm.providers.ollama` for a compact reference
implementation. Adapters also get optional `start`/`stop` lifecycle
hooks — useful if a future provider needs, say, OAuth token acquisition.

### Integrant

The library is stateless, so it doesn't *need* a lifecycle — but if your
app is an Integrant system, add `integrant/integrant` to your deps and
require `kirahowe.clj-llm.integrant` for a ready-made key that loads the
config file and runs each provider's `start`/`stop` hooks:

```clojure
;; system.edn
{:kirahowe.clj-llm/config {:path "llm.edn" :profile :prod}
 :my-app/handler {:llm #ig/ref :kirahowe.clj-llm/config}}
```

```clojure
(defmethod ig/init-key :my-app/handler [_ {:keys [llm]}]
  (fn [request]
    ...
    (llm/generate llm (:question (:params request)))))
```

Integrant is deliberately **not** a dependency of clj-llm — plain-map
users never load it.

## Response reference

`generate` returns:

| Key              | Value                                                        |
|------------------|--------------------------------------------------------------|
| `:text`          | the assistant's reply text                                   |
| `:messages`      | full conversation incl. the reply and any tool rounds        |
| `:tool-calls`    | unhandled tool calls (only when you left `:fn` off)          |
| `:model`         | model id as reported by the provider                         |
| `:provider`      | provider name keyword from your config                       |
| `:usage`         | `{:input-tokens n :output-tokens n}`, summed over tool rounds|
| `:finish-reason` | `:stop`, `:length`, `:tool-calls`, `:refusal`, ...           |
| `:raw`           | the provider's parsed wire response (last round)             |

HTTP failures throw `ex-info` with `{:type :kirahowe.clj-llm.http/error
:status ... :body ...}` in the ex-data.

## Development

All dev affordances are [Babashka](https://babashka.org) tasks — run
`bb tasks` to list them:

| Task                | What it does                                        |
|---------------------|-----------------------------------------------------|
| `bb repl`           | dev REPL with `dev/`, `test/` and integrant loaded  |
| `bb test`           | run the test suite                                  |
| `bb test:integrant` | test suite incl. the optional integrant bindings    |
| `bb lint`           | clj-kondo over `src` and `test`                     |
| `bb fmt` / `bb fmt:fix` | check / fix formatting with cljfmt             |
| `bb ci`             | format check + lint + full tests                    |
| `bb clean`          | delete `target/`                                    |
| `bb jar`            | build the library jar (tools.build)                 |
| `bb install`        | install into the local Maven repo                   |
| `bb deploy`         | deploy to Clojars                                   |

The test suite runs entirely offline: adapters are tested as pure
request-building/response-parsing functions, the tool loop against a
scripted in-memory adapter, and the HTTP/streaming stack end-to-end
against an in-process `com.sun.net.httpserver` standing in for each
provider.

## Design notes

- Dependencies are minimal on purpose: `org.clojure/data.json` is the
  only runtime dependency; HTTP is JDK 11+ `java.net.http`.
- Providers are *accounts/endpoints*; adapters are *wire protocols*.
  Two entries in `:providers` can share an adapter (e.g. OpenAI and
  Groq), which is how one codebase talks to everything.
- The verb is `generate`, not `chat` — a zero-shot completion isn't a
  conversation, and a conversation is just `generate` over accumulated
  messages.

## License

Copyright © 2026 Kira Howe

Distributed under the Eclipse Public License version 2.0.
