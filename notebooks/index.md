::: {.hero}

# clj-llm {.unnumbered .unlisted}

Call LLMs from Clojure with ordinary functions and data.

Generate text, stream responses, call tools, request structured data, create
embeddings, and compare models with evals. The same small API works with
Anthropic, OpenAI-compatible providers, and Ollama.

[Get a response](#quick-start){.btn .btn-primary .btn-lg}
[View on GitHub](https://github.com/kirahowe/clj-llm){.btn .btn-outline-secondary .btn-lg}

:::

::: {.callout-note title="Alpha release"}
clj-llm is ready to try, but the API may still change before `0.1.0`. If
something feels awkward, [please open an issue](https://github.com/kirahowe/clj-llm/issues).
This is exactly when that feedback is most useful.
:::

## Quick start

The quickest way to try clj-llm is with [Ollama](https://ollama.com/). It runs
locally and does not need an API key.

### 1. Add the library

Add the alpha to your `deps.edn`:

```clojure
{:deps {com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}}}
```

### 2. Pull a model

Install Ollama, then download a small model:

```sh
ollama pull llama3.2
```

### 3. Ask it something

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

That is the whole working setup. The provider map tells clj-llm which protocol
to use, and `"ollama/llama3.2"` selects the provider and model. There is no
client to construct and no global state to initialize.

The complete response is a Clojure map:

```clojure
(select-keys (llm/generate config "What is the capital of France?")
             [:llm/text :llm/model :llm/usage :llm/latency-ms])

;; => #:llm{:text "Paris"
;;          :model "llama3.2"
;;          :usage {:input-tokens 17 :output-tokens 2}
;;          :latency-ms 184}
```

It also contains the complete conversation, finish reason, resolved request,
provider name, start time, and original provider response. That makes every
call useful now and measurable later.

If the first call fails, the [Getting started](getting_started.qmd#when-things-go-wrong)
chapter has a short Ollama checklist and explains clj-llm's typed errors.

## What do you want to build?

| I want to... | Start here |
|---|---|
| use Anthropic, OpenAI, or another compatible provider | [Configure a provider](getting_started.qmd#choose-a-provider) |
| keep a multi-turn conversation | [Conversations are data](conversations_and_streaming.qmd#multi-turn-is-just-data) |
| show text as it arrives | [Stream a response](conversations_and_streaming.qmd#streaming) |
| get a value with a predictable shape | [Request structured data](getting_started.qmd#structured-responses) |
| let a model call Clojure functions | [Use tools](tools.qmd) |
| compare a model or prompt change | [Write an eval](evals.qmd) |
| run a complete program | [Try the examples](notebooks/examples.md) |

## One API, a few useful shapes

Continue a conversation by passing the messages from the previous response:

```clojure
(def first-answer
  (llm/generate config "Name a prime number between 100 and 200."))

(llm/generate config
              "Why is it prime?"
              {:llm/messages (:llm/messages first-answer)})
```

Stream text with a callback. The complete response is still returned when the
stream finishes:

```clojure
(defn print-chunk [{:keys [type text]}]
  (when (= :text type)
    (print text)
    (flush)))

(llm/generate config "Tell me a short story."
              {:llm/on-chunk print-chunk})
```

Ask for data that follows a JSON Schema:

```clojure
(-> (llm/generate
     config
     "Give me three names for a coffee shop."
     {:llm/response-format
      {:type :json-schema
       :name "coffee_shop_names"
       :schema {:type "object"
                :properties {:names {:type "array"
                                     :items {:type "string"}}}
                :required ["names"]}}})
    :llm/structured)
;; => {:names ["..." "..." "..."]}
```

Run the same cases against different models or prompts:

```clojure
(require '[clj-llm.eval :as eval])

(-> (eval/run config
              #:llm{:cases [#:llm{:id :capital
                                  :input "What is the capital of France?"
                                  :expected "Paris"}]
                    :variants [#:llm{:id :default
                                     :model "ollama/llama3.2"}]
                    :scorers [:includes]})
    eval/print-summary)
```

## Why clj-llm looks like this

LLM libraries have a tendency to grow into frameworks. clj-llm deliberately
does not. Config, requests, conversations, tools, responses, and eval reports
are all Clojure data, and the public operations are ordinary functions.

This has a few practical consequences:

- the same call works in a REPL, command-line program, Ring handler, or
  background job;
- model aliases keep provider and model names in config instead of spreading
  them through application code;
- conversations live wherever your application already keeps state;
- provider differences stop at a small adapter boundary; and
- every call records the request, response, token use, and timing needed for
  evals, so measuring a change is part of the normal workflow rather than a
  project for later.

The library was inspired by [RubyLLM](https://rubyllm.com/), then rebuilt around
Clojure's strengths: small functions, immutable values, and explicit state.

## Run a complete example

The repository includes three standalone projects using local Ollama:

- `ask` sends one command-line prompt;
- `chat` runs a streaming, multi-turn terminal conversation; and
- `prompt-server` puts `generate` behind a small local Ring endpoint.

Clone the repository and run one from its root:

```sh
git clone https://github.com/kirahowe/clj-llm.git
cd clj-llm
./examples/run ask "Why is the sky blue?"
```

See the [Examples](notebooks/examples.md) chapter for the complete programs and
the commands for chat and the HTTP server.
