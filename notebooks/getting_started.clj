;; # Getting started

;; Start here for provider configuration, requests, model selection, structured responses, embeddings, and errors.

^{:kindly/hide-code true}
(ns getting-started
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]))

;; ## Configuration

;; clj-llm reads configuration from an EDN file, usually named `llm.edn`. It uses [aero](https://github.com/juxt/aero), so environment-specific values can come from `#env` or `#profile`, and fallback values from `#or`.

;; ```clojure
;; #:llm{:providers
;;       {:ollama {:llm/adapter :ollama
;;                 :base-url #or [#env OLLAMA_HOST
;;                                "http://localhost:11434"]}}
;;       :models
;;       {:default    #:llm{:provider :ollama :model "llama3.2"}
;;        :fast       #:llm{:provider :ollama :model "qwen3:8b"}
;;        :embeddings #:llm{:provider :ollama :model "nomic-embed-text"}}
;;       :defaults
;;       #:llm{:model :default
;;             :embedding-model :embeddings
;;             :max-tokens #profile {:dev 1024 :default 4096}}}
;; ```

;; A provider is an account or endpoint. Here it is an Ollama server, and `:llm/adapter :ollama` tells clj-llm to use Ollama's native API.

;; Model aliases such as `:default` and `:fast` keep model ids out of application code. Changing an alias in config changes the model without changing a call site. Unqualified provider settings such as `:base-url` are passed to the adapter. Keys under `:llm/...`, including `:llm/adapter` and optional `:llm/capabilities`, belong to clj-llm.

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

;; Load a config file with `llm/read-config` (aero options such as `:profile` pass through):

;; ```clojure
;; (def config (llm/read-config "llm.edn"))
;; (def config (llm/read-config "llm.edn" {:profile :dev}))
;; ```

;; `read-config` returns a map. You can also build that map yourself or provide it through Integrant.

^{:kindly/hide-code true}
(def config demo/config)

;; ## The first call

;; Pass a prompt string to `generate` and it returns a response map:

(llm/generate config "What is the capital of France?")

;; The most useful response keys are:

;; - `:llm/text`: the reply as a string.
;; - `:llm/messages`: the conversation including the reply. Pass these messages into another call to continue it.
;; - `:llm/usage`: `{:input-tokens n :output-tokens n}`; with tool use, summed over all rounds.
;; - `:llm/finish-reason`: commonly `:stop`, `:length`, `:tool-calls`, or `:refusal`. Providers may return other values.
;; - `:llm/request`, `:llm/latency-ms`, `:llm/started-at`, `:llm/op`: a record of what was called and how long it took. Tool functions are omitted from the stored request; add them again before replaying a tool-using request.
;; - `:llm/raw`: the provider's parsed wire response, when you need something the normalized keys don't carry.

;; ## Requests beyond a string

;; Use a request map when you need more than a prompt. `:llm/prompt` adds a user message, while `:llm/system`, `:llm/max-tokens`, and `:llm/temperature` control the request. The namespaced-map form `#:llm{...}` is shorthand for keys such as `:llm/prompt`:

(llm/generate config #:llm{:system "You are terse."
                           :prompt "Why is the sky blue?"
                           :max-tokens 200
                           :temperature 0.2})

;; The optional third argument is merged into the request. It is useful for changing one setting at a call site:

(llm/generate config "What is 17 * 23?" {:llm/model :fast})

;; Select a model with a config alias, a `"provider/model-id"` string, or an explicit provider/model map:

(:llm/model (llm/generate config "hi" {:llm/model "ollama/qwen3:8b"}))

(:llm/model (llm/generate config "hi" {:llm/model #:llm{:provider :ollama :model "gemma3:4b"}}))

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

;; `embed` takes a string or a sequence of strings, using the `:llm/embedding-model` alias from defaults (override per call with `:llm/model`):

(llm/embed config "a sentence to embed")

;; With a single string you get `:llm/embedding` (one vector) for convenience alongside `:llm/embeddings`.

;; ## When things go wrong

;; Invalid config, requests, and eval suites throw `ex-info` with a readable `:explain` value. Their [malli](https://github.com/metosin/malli) schemas are in `clj-llm.spec`:

(try
  (llm/generate config {:llm/messages "not a vector of messages"})
  (catch Exception e
    {:type (:type (ex-data e))
     :explain (:explain (ex-data e))}))

;; Provider HTTP errors use `{:type :llm/http-error :status ... :body ...}`. Network failures use `:llm/network-error`. Requesting structured output from an adapter or endpoint that does not support it uses `:llm/unsupported-capability`. The [design and compatibility](notebooks/design.md) chapter lists the other error types.

;; Built-in adapters also bound provider responses before retaining them: non-streaming success/error bodies default to 8 MiB, one SSE/NDJSON line to 1 MiB, and a complete stream to 32 MiB. Streaming has a 10-minute whole-body deadline and a 60-second idle-read deadline. Tune these per provider with `:max-response-bytes`, `:max-stream-line-bytes`, `:max-stream-bytes`, `:stream-timeout-ms`, and `:stream-idle-timeout-ms`; existing `:timeout-ms` covers the complete non-streaming request and reaches through headers for a stream. A breached response bound closes the body and throws body-free `{:type :llm/response-limit :limit-kind ... :limit ...}` data.
