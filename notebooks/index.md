# clj-llm {.unnumbered}

One small Clojure API for Anthropic, OpenAI-compatible providers, and Ollama. Generate text, stream responses, call tools, request structured data, and compare models with evals.

## Installation

This alpha currently has no published or tagged immutable consumer coordinate.
The current checkout and its `:local/root` examples are for repository
evaluation only. Consumer installation guidance will become available after a
release tag and a clean Clojars round trip.

## Get a response in a few minutes

Install [Ollama](https://ollama.com/), then pull the model and verify both the
model inventory and the local service:

```sh
ollama pull llama3.2
ollama list
curl -fsS http://localhost:11434/api/version
```

Because no consumer coordinate is available yet, evaluate the library directly
from the current repository checkout. From any starting directory, replace the
path below with the checkout's absolute path:

```sh
cd /absolute/path/to/clj-llm
clojure
```

This is a repository evaluation command, not consumer installation. Changing
directories makes Clojure select the checkout's `deps.edn`, which puts the
project on its classpath. At the REPL, use this minimal inline configuration for
a first working call:

```clojure
(require '[clj-llm.core :as llm])

(def config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(-> (llm/generate config "Why is the sky blue?")
    :llm/text)
;; => "Sunlight is scattered by gases in the atmosphere..."
```

The inline map performs no cwd-dependent config-file lookup. That independence
applies to configuration, not code loading: Clojure still needs the checkout
project on its classpath, as ensured by the launch command above.

Configuration has two layers. `:llm/providers` names available endpoints;
here the provider name is `:ollama`, and `:llm/adapter :ollama` selects the
native Ollama protocol and its default local URL. `:llm/defaults` supplies
request defaults; the model string `ollama/llama3.2` routes to the `ollama`
provider and asks it for the `llama3.2` model.

> **Warning:**
> Provider maps, base URLs, headers, credentials, and endpoint policies are
> trusted application configuration. Never derive them from an HTTP request,
> tenant record, upload, or other untrusted input. Built-in adapters reject
> malformed and non-HTTP(S) destinations before network I/O and support an
> optional host/port allowlist hook, but that hook does not pin DNS results or
> enforce IP ranges; use network egress controls or a custom transport/provider
> when those guarantees are required. Explicit local HTTP remains supported for
> Ollama and compatible development servers.

`generate` returns the full normalized response map, including the text,
conversation messages, model, provider, token use, finish reason, latency,
normalized request, and original provider response.

### Ollama diagnostics

Failures are `ExceptionInfo` values with a typed `ex-data` map:

| Type | Inspect | Operator action |
|---|---|---|
| `:llm/network-error` | `(ex-data e)` includes `:type` and `:url`; `(ex-cause e)` retains the underlying connection, DNS, or socket exception. | Run the version `curl` above. Start or restart Ollama, then correct the configured host, port, or network path if the URL is not reachable. |
| `:llm/http-error` | `(ex-data e)` includes the provider's `:status`, decoded `:body`, and `:url`. | Read the actual status and body, run `ollama list`, pull the configured model if absent, and confirm that the provider/model names match. Exact missing-model responses can vary by Ollama version. |

See [Getting started](getting_started.qmd) to select models, request structured
data, and create embeddings.

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
                    :variants [#:llm{:id :default
                                     :model "ollama/llama3.2"}]
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
