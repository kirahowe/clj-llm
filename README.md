# clj-llm

Call LLMs from Clojure with ordinary functions and data. Generate text, stream responses, call tools, request structured data, create embeddings, and compare models with evals through one small API for Anthropic, OpenAI-compatible providers, and Ollama.

Inspired by [RubyLLM](https://rubyllm.com/), built around Clojure data and functions.

> [!NOTE]
> clj-llm is ready to try, but the public API may still change before `0.1.0`.
> If something feels awkward, this is exactly when that feedback is useful.

## Installation

Add the alpha to your `deps.edn`:

```clojure
{:deps {com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}}}
```

## Get a response in a few minutes

The quickest way to try clj-llm is with [Ollama](https://ollama.com/). It runs
locally and does not need an API key. Install it, then pull a small model:

```sh
ollama pull llama3.2
```

Start a REPL with `clojure`, then paste this in:

```clojure
(require '[clj-llm.core :as llm])

(def config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(-> (llm/generate config "Why is the sky blue?")
    :llm/text)
;; => "Sunlight is scattered by gases in the atmosphere..."
```

That is the whole working setup. `generate` returns a complete Clojure map with
the text, conversation, resolved request, provider, model, token use, finish
reason, timing, and original provider response. There is no client to construct
and no global state to initialize.

The [getting-started guide](https://kirahowe.github.io/clj-llm/getting_started.html)
covers hosted providers, file-backed config, model aliases, typed errors, and an
Ollama troubleshooting checklist.

## Common tasks

Continue a conversation by passing the prior messages in the options map while
keeping the new prompt as the second positional argument:

```clojure
(def first-answer
  (llm/generate config "Name a prime number between 100 and 200."))

(llm/generate config
              "Why is it prime?"
              {:llm/messages (:llm/messages first-answer)})
```

Stream text as it arrives:

```clojure
(defn print-chunk [{:keys [type text]}]
  (when (= :text type)
    (print text)
    (flush)))

(def streaming-response
  (llm/generate config
                "Tell me a short story."
                {:llm/on-chunk print-chunk}))

streaming-response
;; => #:llm{:text "..." :messages [...] ...}
```

The callback receives typed event maps and deliberately prints only `:text`
events. Streaming does not replace the normal result: `streaming-response` is
the complete `generate` return, including `:llm/text` and `:llm/messages`.

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

Create embeddings by selecting an embedding-capable model explicitly. Pull it
once before the first call:

```sh
ollama pull nomic-embed-text
```

```clojure
(llm/embed config "some text"
           {:llm/model "ollama/nomic-embed-text"})
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
         :variants [#:llm{:id :default :model "ollama/llama3.2"}]
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
