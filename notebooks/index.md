# clj-llm {.unnumbered}

One small Clojure API for Anthropic, OpenAI-compatible providers, and Ollama. Generate text, stream responses, call tools, request structured data, and compare models with evals.

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

That is the main API: pass a config and a prompt to `generate`, then read the answer from `:llm/text`. The full response also includes the conversation, model, token use, finish reason, latency, and original provider response.

See [Getting started](getting_started.qmd) to select models, request structured data, and create embeddings.

## Common tasks

Continue a conversation by passing its messages back in:

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

Ask for data that follows a JSON Schema:

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

Run the same cases against different models or prompts:

```clojure
(require '[clj-llm.eval :as eval])

(-> (eval/run config
              #:llm{:cases [#:llm{:id :capital
                                  :input "What is the capital of France?"
                                  :expected "Paris"}]
                    :variants [#:llm{:id :default :model :default}]
                    :scorers [:includes]})
    eval/print-summary)
```

## Start with a complete example

The [Examples](notebooks/examples.md) chapter has three small programs you can run from this repository:

- a one-shot command-line prompt;
- a streaming terminal chat;
- a Ring HTTP endpoint.

## What clj-llm keeps simple

- There are no client or chat objects. Config, requests, conversations, and responses are Clojure data.
- The same functions work in a REPL, command-line program, web handler, or background job.
- Model aliases keep provider and model names in config instead of application code.
- Every call records the request, response, token use, and timing needed for later evaluation.
- Eval suites, scorers, reports, and CI thresholds are part of the library.

clj-llm is currently an alpha. The public API may still change before `0.1.0`.
