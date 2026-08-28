# clj-llm

A small, functional Clojure library for Anthropic, OpenAI-compatible providers, and Ollama. Generate text, stream responses, call tools, request structured data, create embeddings, and compare models with evals.

Inspired by [RubyLLM](https://rubyllm.com/), built around Clojure data and functions.

> [!NOTE]
> clj-llm is currently an alpha. The public API may still change before `0.1.0`.

## Installation

This alpha currently has no published or tagged immutable consumer coordinate.
The current checkout and the `:local/root` dependencies in the [examples](examples/)
are for repository evaluation only. Consumer installation guidance will become
available after a release tag and a clean Clojars round trip.

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

> [!WARNING]
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
