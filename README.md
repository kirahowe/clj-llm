# clj-llm

A small, functional Clojure library for Anthropic, OpenAI-compatible providers, and Ollama. Generate text, stream responses, call tools, request structured data, create embeddings, and compare models with evals.

Inspired by [RubyLLM](https://rubyllm.com/), built around Clojure data and functions.

> [!NOTE]
> clj-llm is currently an alpha. The public API may still change before `0.1.0`.

## Get a response in a few minutes

Add clj-llm to `deps.edn`:

```clojure
{:deps {com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}}}
```

Create `llm.edn`:

```clojure
#:llm{:providers
      {:ollama {:llm/adapter :ollama
                :base-url #or [#env OLLAMA_HOST
                               "http://localhost:11434"]}}
      :models
      {:default #:llm{:provider :ollama
                      :model #or [#env OLLAMA_MODEL "llama3.2"]}}
      :defaults #:llm{:model :default}}
```

> [!WARNING]
> Provider maps, base URLs, headers, credentials, and endpoint policies are
> trusted application configuration. Never derive them from an HTTP request,
> tenant record, upload, or other untrusted input. Built-in adapters reject
> malformed and non-HTTP(S) destinations before network I/O and support an
> optional host/port allowlist hook, but that hook does not pin DNS results or
> enforce IP ranges; use network egress controls or a custom transport/provider
> when those guarantees are required. Explicit local HTTP remains supported for
> Ollama and compatible development servers.

Install [Ollama](https://ollama.com/), pull the model, then start a REPL:

```sh
ollama pull llama3.2
clojure
```

```clojure
(require '[clj-llm.core :as llm])

(def config (llm/read-config "llm.edn"))

(-> (llm/generate config "Why is the sky blue?")
    :llm/text)
;; => "Sunlight is scattered by gases in the atmosphere..."
```

`generate` returns a map containing the text, conversation, model, provider, token use, finish reason, latency, normalized request, and original provider response.

## Common tasks

Continue a conversation:

```clojure
(def first-answer
  (llm/generate config "Name a prime number between 100 and 200."))

(llm/generate config
              {:llm/messages (:llm/messages first-answer)
               :llm/prompt "Why is it prime?"})
```

Stream text as it arrives:

```clojure
(llm/generate config "Tell me a short story."
              {:llm/on-chunk
               (fn [{:keys [type text]}]
                 (when (= :text type)
                   (print text)
                   (flush)))})
```

Ask for a structured response:

```clojure
(llm/generate
 config
 "Give me three names for a coffee shop."
 {:llm/response-format
  {:type :json-schema
   :name "coffee_shop_names"
   :schema {:type "object"
            :properties {:names {:type "array"
                                 :items {:type "string"}}}
            :required ["names"]}}})
;; => #:llm{:structured {:names ["..." "..." "..."]} ...}
```

Let the model call a Clojure function:

```clojure
(llm/generate
 config
 "What is the weather in Berlin?"
 {:llm/tools
  [{:name "get-weather"
    :description "Look up current weather for a city"
    :parameters {:type "object"
                 :properties {:city {:type "string"}}
                 :required ["city"]}
    :fn (fn [{:keys [city]}]
          {:city city :temperature-c 21 :sky "clear"})}]})
```

Tool calls and arguments are untrusted model output. `:parameters` guides the
model; it is not runtime validation or authorization. Automatic batches are
all-or-none and execute at most 10 calls across all rounds by default. Use
`:llm/tool-policy` for authorization, `:llm/tool-argument-validator` for a real
runtime validator, and `:llm/max-tool-calls` for an application-specific total.
Rejected calls stay local under `:llm/tool-rejections`; omit `:fn` when a human
or application workflow should inspect and approve calls manually.

Create embeddings with a configured `:llm/embedding-model`:

```clojure
(llm/embed config "some text")
;; => #:llm{:embedding [0.01 ...] ...}
```

Run cases against different models, prompts, or settings:

```clojure
(require '[clj-llm.eval :as eval])

(def report
  (eval/run
   config
   #:llm{:cases [#:llm{:id :capital
                       :input "What is the capital of France?"
                       :expected "Paris"}]
         :variants [#:llm{:id :default :model :default}]
         :scorers [:includes]}))

(eval/print-summary report)
```

Qualified task or scorer symbols are executable JVM code and are rejected by
default. Only trusted suites should opt in with `{:allow-code? true}` (or
`--allow-code` through `bb eval`); use a restricted process or container when
the suite needs isolation.

## Providers

| Adapter | Use it for |
|---|---|
| `:anthropic` | Anthropic Messages API |
| `:openai` | OpenAI Chat Completions and compatible services such as OpenRouter, Groq, Together, vLLM, and LM Studio |
| `:ollama` | Ollama's native chat and embedding APIs |

All three adapters support generation, streaming, tools, and JSON Schema responses. The OpenAI and Ollama adapters also support embeddings. Features offered by an OpenAI-compatible endpoint still depend on that service and model.

## Run a complete example

The [`examples/`](examples/) directory contains three standalone projects:

- [`ask`](examples/ask/) reads one prompt from the command line or standard input;
- [`chat`](examples/chat/) runs a streaming, multi-turn terminal chat;
- [`prompt-server`](examples/prompt-server/) puts `generate` behind a Ring and Jetty HTTP endpoint.

They use Ollama so they can run locally without an API key.

## Documentation

The [full guide](https://kirahowe.github.io/clj-llm/) covers configuration, conversations, streaming, tools, evals, custom provider adapters, and compatibility rules.

## Development

```sh
bb test             # test the library
bb test:integrant   # include the optional Integrant bindings
bb lint             # clj-kondo
bb fmt              # check formatting
```

## License

[MIT](LICENSE)
