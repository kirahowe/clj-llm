;; # Getting started

;; This chapter walks from an empty project to a first response, and then takes the response map apart. Everything on this page executes when the book is rendered, against the canned `book.demo` provider, so it runs offline. With a real config the code is identical.

(ns getting-started
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]))

;; ## Configuration

;; clj-llm is configured by a plain EDN map, conventionally kept in a file called `llm.edn` and read with [aero](https://github.com/juxt/aero), which gives you `#env` for secrets, `#profile` for per-environment values, `#or` for fallbacks, and the rest of aero's tag set. A realistic config looks like this:

;; ```clojure
;; #:llm{:providers
;;       {:anthropic {:llm/adapter :anthropic
;;                    :api-key #env ANTHROPIC_API_KEY}
;;        :groq {:llm/adapter :openai
;;               :base-url "https://api.groq.com/openai/v1"
;;               :api-key #env GROQ_API_KEY}
;;        :local {:llm/adapter :ollama
;;                :base-url #or [#env OLLAMA_HOST "http://localhost:11434"]}}
;;       :models
;;       {:smart #:llm{:provider :anthropic :model "claude-sonnet-4-6"}
;;        :fast  #:llm{:provider :groq :model "llama-3.3-70b-versatile"}}
;;       :defaults
;;       #:llm{:model :smart
;;             :max-tokens #profile {:dev 1024 :default 4096}}}
;; ```

;; Three ideas are packed in there. **Providers** are accounts or endpoints: an Anthropic account, a Groq account, an Ollama server on your LAN. Each names an **adapter** (`:llm/adapter`), which is the wire protocol to speak; the `:openai` adapter covers every OpenAI-compatible service, which is most of them, so two providers often share one adapter. **Model aliases** let application code ask for an intent (`:smart`, `:fast`) while the config decides which vendor and model that currently means; swapping providers is a config edit, not a code change. Within a provider map, everything other than `:llm/adapter` (like `:api-key` and `:base-url`) belongs to that adapter and flows through untouched.

;; Load a config file with `llm/read-config` (aero options such as `:profile` pass through):

;; ```clojure
;; (def config (llm/read-config "llm.edn"))
;; (def config (llm/read-config "llm.edn" {:profile :dev}))
;; ```

;; The result is just a map, and nothing downstream cares where it came from: hand-written maps, aero, or an integrant system key all work identically. For this book we use the demo config, which is shaped exactly like the real one above but points at a canned in-process adapter:

(def config demo/config)

;; ## The first call

;; `generate` is the whole API for text: config in, prompt in, response map out. A plain string is a zero-shot prompt:

(llm/generate config "What is the capital of France?")

;; That map is worth reading carefully, because it is the library's central data structure. The library's keys are all namespaced `:llm/...` (any other key in maps you build or store is yours forever; see the design chapter), and the interesting ones are:

;; - `:llm/text`: the reply, as a string. This is the accessor to reach for; it stays stable even as message internals grow richer.
;; - `:llm/messages`: the full conversation including the reply, as plain `{:role ... :content ...}` maps. Conj your next user message onto this to continue the conversation.
;; - `:llm/usage`: `{:input-tokens n :output-tokens n}`; with tool use, summed over all rounds.
;; - `:llm/finish-reason`: `:stop`, `:length`, `:tool-calls`, `:refusal`, ... an open set, so handle unknown keywords gracefully.
;; - `:llm/request`, `:llm/latency-ms`, `:llm/started-at`, `:llm/op`: the response doubles as a complete, replayable *interaction record*. This is the foundation the eval system builds on, and the evals chapter picks it up from here.
;; - `:llm/raw`: the provider's parsed wire response, when you need something the normalized keys don't carry.

;; ## Requests beyond a string

;; A request can be a map. `:llm/prompt` appends a user message to `:llm/messages` — with no messages it's simply the prompt, and alongside messages it's the next conversational turn; `:llm/system`, `:llm/max-tokens` and `:llm/temperature` do what they say; the namespaced-map literal `#:llm{...}` keeps it tidy:

(llm/generate config #:llm{:system "You are terse."
                           :prompt "Why is the sky blue?"
                           :max-tokens 200
                           :temperature 0.2})

;; A third argument merges into the request, which is the idiomatic way to tweak one thing per call site:

(llm/generate config "What is 17 * 23?" {:llm/model :fast})

;; Models can be picked per call three ways: an alias keyword from config, a `"provider/model-id"` string (split on the first slash, so model ids containing slashes work), or an explicit map:

(:llm/model (llm/generate config "hi" {:llm/model "demo/demo-custom-7"}))

(:llm/model (llm/generate config "hi" {:llm/model #:llm{:provider :demo :model "demo-inline-2"}}))

;; ## Embeddings

;; `embed` takes a string or a sequence of strings, using the `:llm/embedding-model` alias from defaults (override per call with `:llm/model`):

(llm/embed config "a sentence to embed")

;; With a single string you get `:llm/embedding` (one vector) for convenience alongside `:llm/embeddings`.

;; ## When things go wrong

;; Malformed inputs fail fast at the boundary. Every public contract has a [malli](https://github.com/metosin/malli) schema (see the `clj-llm.spec` namespace), and violations throw `ex-info` with a humanized `:explain`:

(try
  (llm/generate config {:llm/messages "not a vector of messages"})
  (catch Exception e
    {:type (:type (ex-data e))
     :explain (:explain (ex-data e))}))

;; HTTP failures from providers throw `ex-info` with `{:type :llm/http-error :status ... :body ...}`, where `:body` is the parsed error body, so a 401's message is right there in the ex-data. The full list of stable error types is in the design chapter.
