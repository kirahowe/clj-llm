;; # Getting started

;; This chapter starts with provider configuration, then works outward from one call to model aliases, structured responses, embeddings, and errors.

^{:kindly/hide-code true}
(ns getting-started
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]
            [clojure.java.io :as io]))

^{:kindly/hide-code true}
(def config demo/config)

;; ## Installation
;;
;; Add the alpha to your `deps.edn`:
;;
;; ```clojure
;; {:deps {com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}}}
;; ```
;;
;; Then require the public API wherever you need it:
;;
;; ```clojure
;; (require '[clj-llm.core :as llm])
;; ```

;; ## Choose a provider

;; A **provider** is an account or endpoint. An **adapter** is the protocol used to talk to it. This distinction is useful because OpenAI-compatible services can all share the `:openai` adapter, while an application can still give each endpoint a meaningful provider name.

;; You only need to configure the providers you actually use.

;; ### Ollama

;; Ollama is the easiest way to make a local call without an API key. Install [Ollama](https://ollama.com/), then pull a model:
;;
;; ```shell
;; ollama pull llama3.2
;; ```
;;
;; Its smallest useful configuration is the one from the quick start:
;;
;; ```clojure
;; #:llm{:providers {:ollama {:llm/adapter :ollama}}
;;       :defaults #:llm{:model "ollama/llama3.2"}}
;; ```
;;
;; The `:ollama` entry names the provider. `:llm/adapter :ollama` selects Ollama's native API and its default local URL. The model string pairs that provider name with Ollama's model id.

;; ### Anthropic

;; The Anthropic adapter uses the Messages API. Keep the API key in the environment and read it from an Aero config file rather than committing it:
;;
;; ```clojure
;; #:llm{:providers {:anthropic {:llm/adapter :anthropic
;;                                :api-key #env ANTHROPIC_API_KEY}}
;;       :defaults #:llm{:model "anthropic/claude-sonnet-4-6"}}
;; ```

;; ### OpenAI and compatible services

;; The `:openai` adapter speaks the Chat Completions protocol. It works with OpenAI itself and with compatible services such as OpenRouter, Groq, Together, vLLM, and LM Studio. For OpenAI:
;;
;; ```clojure
;; #:llm{:providers {:openai {:llm/adapter :openai
;;                            :api-key #env OPENAI_API_KEY}}
;;       :defaults #:llm{:model "openai/gpt-4.1-mini"}}
;; ```
;;
;; [`gpt-4.1-mini`](https://developers.openai.com/api/docs/models/gpt-4.1-mini) supports Chat Completions, streaming, function calling, and structured outputs. For another compatible service, add its `:base-url` and use the model id that service expects.

;; ## The first call

;; Pass a config and prompt string to `generate`. The examples in this book run against a small deterministic provider so the book can build without a network connection, but the call is exactly the same with any configuration above:

;; ```clojure
;; (def config
;;   #:llm{:providers {:ollama {:llm/adapter :ollama}}
;;         :defaults #:llm{:model "ollama/llama3.2"}})
;;
;; (llm/generate config "What is the capital of France?")
;; ```

^{:kindly/hide-code true}
(llm/generate config "What is the capital of France?")

;; This works! `generate` returns the complete response rather than just the text. The keys you will use most often are:

;; - `:llm/text`: the reply as a string.
;; - `:llm/messages`: the conversation including the reply. Pass these messages into another call to continue it.
;; - `:llm/usage`: `{:input-tokens n :output-tokens n}`; with tool use, summed over all rounds.
;; - `:llm/finish-reason`: commonly `:stop`, `:length`, `:tool-calls`, or `:refusal`. Providers may return other values.
;; - `:llm/request`, `:llm/latency-ms`, `:llm/started-at`, `:llm/op`: what was called and how long it took. Tool functions are omitted from the stored request; add them again before replaying a tool-using request.
;; - `:llm/raw`: the provider's parsed wire response, when you need something the normalized keys don't carry.

;; ## Per-call options

;; Keep the prompt positional and put request options in the third argument:

(llm/generate config
              "Why is the sky blue?"
              #:llm{:system "You are terse."
                    :max-tokens 200
                    :temperature 0.2})

;; A `"provider/model-id"` string selects a different model for one call. The
;; complete response records the resolved selection under `:llm/model`:

;; ```clojure
;; (llm/generate config "What is 17 * 23?"
;;               {:llm/model "ollama/qwen3:8b"})
;; ```

;; ## Growing the configuration

;; Once the inline map works, load the bundled example from a fixed classpath
;; resource:

(def resource-config
  "Configuration loaded from the bundled classpath example."
  (llm/read-config
   (io/resource "clj-llm/config.example.edn")))

;; ```clojure
;; (llm/generate resource-config "Summarize this paragraph.")
;; ```

;; `io/resource` from `clojure.java.io` resolves the fixed name from the
;; classpath instead of the process working directory. `read-config` returns
;; the same map shape as the inline configuration.
;;
;; For application-owned configuration, copy the example to `llm.edn` and grow
;; it with [aero](https://github.com/juxt/aero). Values can come from `#env`,
;; `#profile`, `#or`, `#include`, and `#ref`. This larger example preserves
;; application-facing aliases and an embedding default while adding a second
;; provider:

;; ```clojure
;; #:llm{:providers
;;       {:ollama {:llm/adapter :ollama
;;                 :base-url #or [#env OLLAMA_HOST
;;                                "http://localhost:11434"]}
;;        :anthropic {:llm/adapter :anthropic
;;                    :api-key #env ANTHROPIC_API_KEY}}
;;       :models
;;       {:default    #:llm{:provider :ollama :model "llama3.2"}
;;        :fast       #:llm{:provider :ollama :model "qwen3:8b"}
;;        :careful    #:llm{:provider :anthropic
;;                          :model "claude-sonnet-4-6"}
;;        :embeddings #:llm{:provider :ollama :model "nomic-embed-text"}}
;;       :defaults
;;       #:llm{:model :default
;;             :embedding-model :embeddings
;;             :max-tokens #profile {:dev 1024 :default 4096}}}
;; ```

;; A provider is an account or endpoint. Model aliases such as `:default` and
;; `:fast` keep model ids out of application code. Unqualified provider
;; settings such as `:base-url` and `:api-key` are passed to the adapter. Keys
;; under `:llm/...`, including `:llm/adapter` and optional
;; `:llm/capabilities`, belong to clj-llm.

;; Load the file directly, optionally selecting an Aero profile:

;; ```clojure
;; (def config (llm/read-config "llm.edn"))
;; (def dev-config (llm/read-config "llm.edn" {:profile :dev}))
;;
;; (llm/generate config "Summarize this paragraph." {:llm/model :fast})
;; ```

;; `read-config` returns the same map shape as the inline configuration. In an
;; Integrant application, require `clj-llm.integrant` to register
;; `:clj-llm/config`, then compose it with the keys that consume it:

;; ```clojure
;; (require '[clj-llm.integrant]
;;          '[integrant.core :as ig])
;;
;; (def system
;;   (ig/init
;;    {:clj-llm/config {:path "llm.edn" :profile :prod}
;;     :my.app/handler {:llm (ig/ref :clj-llm/config)}}))
;; ```

;; The consuming key's `ig/init-key` method receives the initialized clj-llm
;; config under `:llm`. Halting the system stops provider lifecycle resources.

;; ### Trusted destinations

;; > **Security:** Provider maps, `:base-url`, `:headers`, credentials, and `:endpoint-policy` are trusted application configuration. Never derive them from an HTTP request, tenant record, upload, or other untrusted input: built-in adapters send prompts and credentials to the configured destination.
;;
;; Built-in requests accept only absolute `http` or `https` destinations with a host and without user-info, a query, or a fragment. This deliberately preserves explicit HTTP for Ollama and compatible development servers. For a hosted deployment, application code may add an optional provider `:endpoint-policy` function (functions are not EDN) to restrict the normalized destination:
;;
;; ```clojure
;; {:endpoint-policy
;;  (fn [{:keys [scheme host port path]}]
;;    (and (= "https" scheme)
;;         (= "llm-gateway.example" host)
;;         (= 443 port)
;;         (.startsWith ^String path "/v1/")))}
;; ```
;;
;; The hook runs for generation, streaming, and embeddings before the shared transport constructs the JDK request or performs network I/O. Its immutable map contains lower-case `:scheme` and `:host`, an effective `:port` (80/443 when omitted), and normalized raw `:path` (default `"/"`); IPv6 hosts have no URI brackets. False or nil throws `:llm/endpoint-rejected`, and an exception is wrapped as `:llm/endpoint-policy-error`. Invalid destinations throw `:llm/invalid-endpoint` with a safe `:reason`; none of these local error maps contains the URL, headers, credentials, or prompt.
;;
;; This hook does not resolve or pin a hostname to the checked address. It therefore cannot safely enforce IP/network ranges against DNS rebinding. Use network egress controls or a custom transport/provider that pins the checked address when stronger guarantees are required.

;; ## Advanced request maps

;; Positional prompts plus options are the ordinary API. Use a request map when
;; the application already has role-level messages and must preserve them
;; exactly—for example, when importing an existing transcript containing an
;; assistant turn:

(llm/generate
 config
 {:llm/messages [{:role :user :content "Call me Rowan."}
                 {:role :assistant :content "Hello, Rowan."}
                 {:role :user :content "What name did I give you?"}]})

;; ## Structured responses

;; Use `:llm/response-format` to ask for a JSON response with a particular shape. clj-llm sends the schema to the provider unchanged. Providers and models support different parts of JSON Schema, so check the current [OpenAI](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), [Anthropic](https://platform.claude.com/docs/en/build-with-claude/structured-outputs), or [Ollama](https://docs.ollama.com/capabilities/structured-outputs) documentation when a schema is rejected.

(llm/generate
 config
 "Return the capital of France and your confidence."
 {:llm/response-format
  {:type :json-schema
   :name "capital_answer"
   :schema {:type "object"
            :properties {:capital {:type "string"}
                         :confidence {:type "number"}}
            :required ["capital" "confidence"]}}})

;; The original JSON remains in `:llm/text`, and the decoded value is in `:llm/structured`. Object keys become Clojure keywords. clj-llm checks that the answer is valid JSON, but it does not validate the decoded value against the schema or your application rules.

;; Invalid JSON is returned as `:llm/structured-error` instead of throwing, and the original text is preserved. Check `:llm/finish-reason` as well: a response stopped by a token limit can still contain valid JSON. JSON `null` is represented by a present `:llm/structured` key with a nil value, so use `contains?` if you need to distinguish it from a missing structured response.

;; ## Embeddings

;; The minimal inline config has no embedding default, so select the model in
;; opts. The later file-backed config's `:llm/embedding-model` default lets
;; applications omit this option:

;; ```clojure
;; (llm/embed config "a sentence to embed"
;;            {:llm/model "ollama/nomic-embed-text"})
;; ```

;; With a single string you get `:llm/embedding` (one vector) for convenience alongside `:llm/embeddings`.

;; ## When things go wrong

;; If a local Ollama call fails, check the two things clj-llm needs: the model is installed, and the HTTP service is reachable:
;;
;; ```shell
;; ollama list
;; curl -fsS http://localhost:11434/api/version
;; ```
;;
;; An unreachable service throws `ex-info` with `:type :llm/network-error`. A provider response with an unsuccessful status throws `:llm/http-error` and includes its `:status` and decoded `:body`. Read that body before guessing: a model error can mean the name is wrong, the model is missing from this Ollama instance, or the request reached a different server than expected.

;; Invalid config, requests, and eval suites throw `ex-info` with a readable `:explain` value. Their [malli](https://github.com/metosin/malli) schemas are in `clj-llm.spec`:

(try
  (llm/generate config "Use an invalid temperature."
                {:llm/temperature "hot"})
  (catch Exception e
    {:type (:type (ex-data e))
     :explain (:explain (ex-data e))}))

;; Provider HTTP errors use `{:type :llm/http-error :status ... :body ...}`. Network failures use `:llm/network-error`. Requesting structured output from an adapter or endpoint that does not support it uses `:llm/unsupported-capability`. The [design and compatibility](notebooks/design.md) chapter lists the other error types.

;; Built-in adapters also bound provider responses before retaining them: non-streaming success/error bodies default to 8 MiB, one SSE/NDJSON line to 1 MiB, and a complete stream to 32 MiB. Streaming has a 10-minute whole-body deadline and a 60-second idle-read deadline. Tune these per provider with `:max-response-bytes`, `:max-stream-line-bytes`, `:max-stream-bytes`, `:stream-timeout-ms`, and `:stream-idle-timeout-ms`; existing `:timeout-ms` covers the complete non-streaming request and reaches through headers for a stream. A breached response bound closes the body and throws body-free `{:type :llm/response-limit :limit-kind ... :limit ...}` data.
