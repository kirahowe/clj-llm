# assay

A small, functional Clojure library for calling large language models —
any model, from any provider, including local ones — with evals built in
from the ground up. An *assay* is a test of quality and composition:
this library's premise is that you can't build well with LLMs unless
measuring what they do is as easy as calling them.

Inspired by [RubyLLM](https://rubyllm.com), rebuilt on Clojure values:

- **Stateless and functional.** No client objects, no global mutable
  state, no sessions. Every function takes a config map and returns data.
- **Context agnostic.** Because nothing is stateful, the same calls work
  in a web app, a CLI, a background job, or a one-off REPL experiment.
- **Config lives in config files.** API keys, base URLs, model choices —
  all of it comes from EDN config read with
  [aero](https://github.com/juxt/aero) (`#env` for secrets), never from
  source code.
- **Evals are first class.** You can't pick "the best" model or prompt
  without measuring. Every response is a complete, replayable
  interaction record, and `assay.eval` runs cases × variants into
  scored, comparable summaries — for single calls or for whole systems
  that contain LLM calls.
- **Conversations are data.** A conversation is a vector of message
  maps. Multi-turn just means passing the previous `:assay/messages`
  back in — no chat-object ceremony, and a zero-shot question isn't
  pretending to be a chat.
- **One protocol away from any provider.** Adapters are multimethods;
  the OpenAI-compatible adapter alone covers most of the hosted and
  self-hosted ecosystem, and adding a new adapter is a page of code.
- **Compatible forever.** Every contract is spec'd with
  [malli](https://github.com/metosin/malli) schemas, the keyspace is
  partitioned so your keys can never collide with future library keys,
  and the [compatibility promises](#compatibility-promises) below are
  part of the API.

Built on the JDK's own `java.net.http` client plus
[charred](https://github.com/cnuernber/charred),
[aero](https://github.com/juxt/aero) and
[malli](https://github.com/metosin/malli).

## Installation

Not yet on Clojars. Use it as a git dependency:

```clojure
;; deps.edn
{:deps {com.kirahowe/assay {:git/url "https://github.com/kirahowe/clj-llm"
                            :git/sha "..."}}}
```

## The keyspace rule

One rule to know before reading any example, and the reason this library
can promise not to break you:

- Every key the library defines in maps you author or store — config,
  requests, responses, eval suites, reports — is namespaced `:assay/...`.
  Any *other* key in those maps (unqualified, or namespaced by you) is
  yours: the library will never assign meaning to it.
- Conversation-shaped structures — messages, tool definitions, tool
  calls, usage, stream chunks, scorer results — keep their plain,
  industry-standard keys (`:role`, `:content`, `:name`, `:score`, ...).
  The *plain* keyspace inside those structures is reserved by the
  library; extend them only with your own namespaced keys.

Clojure's namespaced-map literal keeps the qualified form light:
`#:assay{:prompt "hi" :model :fast}` reads as
`{:assay/prompt "hi" :assay/model :fast}`.

## Configuration

Create an EDN config file (conventionally `llm.edn`). It's read with
aero, so the full aero tag set is available — `#env`, `#or`, `#profile`,
`#include`, `#ref`, ... A full example ships at
[`resources/assay/config.example.edn`](resources/assay/config.example.edn):

```clojure
#:assay{:providers
        {:anthropic {:assay/adapter :anthropic
                     :api-key #env ANTHROPIC_API_KEY}

         ;; the :openai adapter speaks the OpenAI chat-completions protocol,
         ;; so it covers OpenAI, OpenRouter, Groq, Together, vLLM, LM Studio...
         :groq {:assay/adapter :openai
                :base-url "https://api.groq.com/openai/v1"
                :api-key #env GROQ_API_KEY}

         ;; local models through Ollama's native API
         :local {:assay/adapter :ollama
                 :base-url #or [#env OLLAMA_HOST "http://localhost:11434"]}}

        ;; aliases: code names an intent (:smart, :fast); config decides what
        ;; that means. Swap providers without touching code.
        :models
        {:smart #:assay{:provider :anthropic :model "claude-sonnet-4-6"}
         :fast  #:assay{:provider :groq :model "llama-3.3-70b-versatile"}}

        :defaults
        #:assay{:model :smart
                :max-tokens #profile {:dev 1024 :default 4096}}}
```

Within a provider map, `:assay/adapter` selects the wire protocol; every
other key (`:api-key`, `:base-url`, ...) belongs to that adapter and
flows through untouched — including to your own custom adapters.

Load it with:

```clojure
(require '[assay.core :as assay])

(def config (assay/read-config "llm.edn"))                  ; or any io/reader-able source
(def config (assay/read-config "llm.edn" {:profile :dev}))  ; aero options pass through
```

The result is a plain map — configs built by hand, by aero directly, or
inside an integrant system all work identically downstream.

## Usage

### Generating text

```clojure
;; zero-shot: a prompt in, a response map out
(assay/generate config "Why is the sky blue?")
;; => #:assay{:text "Sunlight scattering..."
;;            :messages [{:role :user :content "Why is the sky blue?"}
;;                       {:role :assistant :content "Sunlight scattering..."}]
;;            :model "claude-sonnet-4-6"
;;            :provider :anthropic
;;            :usage {:input-tokens 13 :output-tokens 42}
;;            :finish-reason :stop
;;            :request {...} :latency-ms 640 :started-at #inst "..." :op :generate
;;            :raw {...}}

;; pick a model per call — by alias, "provider/model" string, or map
(assay/generate config "Say hi." {:assay/model :fast})
(assay/generate config "Say hi." {:assay/model "local/llama3.2"})
(assay/generate config "Say hi." {:assay/model #:assay{:provider :groq :model "qwen-2.5-72b"}})

;; everything else is a request key
(assay/generate config #:assay{:system "You are terse."
                               :prompt "Explain monads."
                               :max-tokens 200
                               :temperature 0.2})
```

### Multi-turn conversations

A conversation is the `:assay/messages` vector. Continue one by conj-ing
the next user message onto the previous response's messages:

```clojure
(def r1 (assay/generate config "Name a prime number between 100 and 200."))

(assay/generate config
                {:assay/messages (conj (:assay/messages r1)
                                       {:role :user :content "Why is it prime?"})})
```

Store that vector wherever your context keeps state — a Ring session, an
atom, a database row. The library doesn't care. Message maps are part of
the frozen contract (see `assay.spec`), so conversations you persist
today stay readable by every future version.

### Streaming

Pass an `:assay/on-chunk` callback. Each chunk has a `:type`; text
deltas are `{:type :text :text "delta"}`. New chunk types may appear in
future versions (tool-call deltas, thinking, round boundaries), so
**ignore chunks whose type you don't recognize** — that's what keeps
your callback forward-compatible. The complete response map is still
returned at the end.

```clojure
(assay/generate config "Tell me a story."
                {:assay/on-chunk (fn [{:keys [type text]}]
                                   (when (= :text type)
                                     (print text) (flush)))})
```

### Tools (function calling)

Tools are maps. If every tool the model calls has a `:fn`, assay runs
the call, feeds the result back, and loops (bounded by
`:assay/max-tool-rounds`, default 10) until the model answers:

```clojure
(assay/generate config "What's the weather in Berlin?"
                {:assay/tools [{:name "get-weather"
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
with `:assay/tool-calls` and `:assay/finish-reason :tool-calls`, and you
append `{:role :tool :tool-call-id id :content result}` messages
yourself — useful when tool execution needs approval, queueing, or your
own loop.

### Embeddings

```clojure
(assay/embed config "some text")            ; => #:assay{:embedding [0.01 ...] ...}
(assay/embed config ["chunk 1" "chunk 2"])  ; => #:assay{:embeddings [[...] [...]] ...}
```

Uses the `:assay/embedding-model` alias from `:assay/defaults`; override
per call with `{:assay/model ...}`.

## Evals

There is no iterating toward better models/prompts/parameters without
measuring, so evals are part of the core design, in two layers.

### 1. Every call collects what evals need

Every `generate`/`embed` response doubles as an **interaction record**:
alongside the result it carries the fully resolved `:assay/request`
(replayable — tool functions scrubbed), `:assay/usage`,
`:assay/latency-ms`, `:assay/started-at` and `:assay/op`. To collect
records from live traffic, set a hook once in config:

```clojure
#:assay{:defaults #:assay{:model :smart
                          :on-interaction my.app/store-interaction!}}  ; fn of one record
```

Collected records replay directly as eval cases, since a case accepts
the same `:assay/messages` a record carries.

### 2. Suites: cases × variants → scored comparison

A suite is plain data — inline or an EDN file (read with the same aero
reader as config). Cases say *what to test*, variants say *what to
compare* (any bundle of request keys: model, system prompt, temperature,
tools...), scorers say *what good looks like*:

```clojure
;; evals/suite.edn
#:assay{:cases    [#:assay{:id :capital
                           :input "What is the capital of France?"
                           :expected "Paris"}]
        :variants [#:assay{:id :baseline :model :smart}
                   #:assay{:id :cheap    :model :fast}
                   #:assay{:id :terse    :model :smart :system "Answer in one word."}]
        :scorers  [:includes]
        ;; optional: minimum mean score per scorer — the CLI exits
        ;; non-zero below this, so a suite can gate CI like a test suite
        :thresholds {:includes 0.9}}
```

```clojure
(require '[assay.eval :as eval])

(def report (eval/run config "evals/suite.edn"))

(eval/print-summary report)
;; variant    model                   cases  errors  includes  latency(mean ms)  in-tok  out-tok
;; ---------  ----------------------  -----  ------  --------  ----------------  ------  -------
;; :baseline  claude-sonnet-4-6       3      0       1.000     642               118     57
;; :cheap     llama-3.3-70b-versatile 3      0       0.667     97                118     41
;; :terse     claude-sonnet-4-6       3      0       1.000     512               130     12
```

The report is data — per case×variant results with full responses, a
per-variant summary, and provenance (`:assay/run-at`, resolved model
ids, case/variant counts) so a stored report is comparable with next
month's. From the shell: `bb eval evals/suite.edn`.

### Scoring

Built-ins `:exact-match`, `:includes`, `:matches` cover mechanical
ground truth. Any function of `{:config _ :case _ :variant _
:response _}` returning `{:score 0.0-1.0}` works, and EDN suites can
reference your scorers as qualified symbols (`my.app.evals/grounded?`),
resolved at run time. For subjective quality, model-graded scoring is
one call away:

```clojure
(eval/run config
          #:assay{:cases cases
                  :variants variants
                  :scorers [:includes
                            (eval/llm-judge {:model :smart
                                             :criteria "Factually accurate, and answers the question directly."})]})
```

### Evals for whole systems, not just calls

By default a case×variant runs one `generate` call. Real questions are
usually bigger: is my *pipeline* — retrieval, prompt assembly, the
model, post-processing — good? Point `:assay/task` at any function of
`{:keys [config case variant]}` that returns a response-shaped map and
the same cases, scorers, thresholds and reports apply to your whole
system:

```clojure
(eval/run config
          #:assay{:cases cases
                  :task (fn [{:keys [config case]}]
                          (my.rag/answer config (:assay/input case)))  ; returns the generate response
                  :scorers [(eval/llm-judge {:criteria "Grounded in the retrieved context."})]})
```

In EDN suites, `:assay/task` can be a qualified symbol too.

The intended workflow: start with the defaults (copy
[`resources/assay/eval-suite.example.edn`](resources/assay/eval-suite.example.edn)),
grow cases from real traffic via `:assay/on-interaction`, set thresholds
so quality regressions fail CI, and let every model/prompt/pipeline
change be a benchmarked decision instead of a vibe.

## Extending

### Adding a provider adapter

An adapter is a couple of multimethod implementations, dispatching on
the `:assay/adapter` key of a provider config:

```clojure
(require '[assay.provider :as provider])

(defmethod provider/generate! :my-adapter
  [provider-config request opts]
  ;; take the normalized request, speak your wire protocol,
  ;; return {:message {...} :usage {...} :finish-reason ... :raw ...}
  ...)
```

See the `assay.provider` docstring for the full contract **and the
compatibility rules the library commits to** (new request keys are
ignorable, new result keys optional, multimethod signatures frozen —
anything new arrives inside the `request` or `opts` maps). The
`assay.providers.ollama` source is a compact reference implementation.

### Integrant

The library is stateless, so it doesn't *need* lifecycle management —
but if your app is an integrant system, add `integrant/integrant` to
your deps and require `assay.integrant` for a ready-made key that loads
the config file (aero options like `:profile` pass through):

```clojure
;; system.edn
{:assay/config {:path "llm.edn" :profile :prod}
 :my-app/handler {:llm #ig/ref :assay/config}}
```

```clojure
(defmethod ig/init-key :my-app/handler [_ {:keys [llm]}]
  (fn [request]
    ...
    (assay/generate llm (:question (:params request)))))
```

Init/halt also run each provider's `start`/`stop` multimethod hooks
(no-ops for the built-in adapters) — the escape hatch if a future
adapter ever needs real state like OAuth token refresh. Integrant is
deliberately **not** a dependency of assay; plain-map users never load
it.

## Response reference

`generate` returns (all under the `:assay/` namespace):

| Key                    | Value                                                        |
|------------------------|--------------------------------------------------------------|
| `:assay/text`          | the assistant's reply text                                   |
| `:assay/messages`      | full conversation incl. the reply and any tool rounds        |
| `:assay/tool-calls`    | unhandled tool calls (only when you left `:fn` off)          |
| `:assay/model`         | model id as reported by the provider                         |
| `:assay/provider`      | provider name keyword from your config                       |
| `:assay/usage`         | `{:input-tokens n :output-tokens n}`, summed over tool rounds|
| `:assay/finish-reason` | `:stop`, `:length`, `:tool-calls`, `:refusal`, ... (open set)|
| `:assay/request`       | the fully resolved, replayable request                       |
| `:assay/latency-ms`    | wall-clock duration of the call                              |
| `:assay/started-at`    | `java.time.Instant` the call began                           |
| `:assay/op`            | `:generate` (or `:embed`)                                    |
| `:assay/raw`           | the provider's parsed wire response (last round)             |

All contracts are also expressed as malli schemas in `assay.spec` —
that namespace is the precise, machine-checkable version of this table.

Errors throw `ex-info`; the ex-data `:type` values are part of the
stable API: `:assay/http-error` (with `:status` and `:body`),
`:assay/invalid-request`, `:assay/invalid-config`, `:assay/config-error`,
`:assay/unknown-adapter`, `:assay/missing-api-key`, `:assay/unsupported`,
`:assay/stream-error`, `:assay/invalid-suite`, `:assay/unknown-scorer`,
`:assay/invalid-case`, `:assay/config-not-found`.

## Compatibility promises

These are commitments, not aspirations; extending the library must never
require breaking them:

1. **Your keys are yours.** Non-`:assay/` keys in boundary maps and
   non-reserved keys in protocol structures will never gain library
   meaning.
2. **Stored data stays readable.** Message vectors and interaction
   records you persist today remain valid inputs forever; message
   `:content` is a string today and a vector of typed content parts is
   already reserved for multimodal futures.
3. **Callbacks are type-tagged.** `:assay/on-chunk` payloads always
   carry `:type`; new types may appear, existing ones keep their shape.
   A callback that ignores unknown types never breaks.
4. **Adapters keep working.** New request keys are ignorable, new result
   keys optional, multimethod signatures frozen (context travels in the
   `request`/`opts` maps), and new multimethods always ship with
   `:default` implementations.
5. **Error types are stable.** The `:type` keywords above never change
   meaning or disappear.
6. **Unknown `:assay/` keys may become errors.** Don't invent keys in
   the `:assay/` namespace; validation may tighten around them in any
   release. (This is what makes 1–5 keepable.)

## Development

All dev affordances are [Babashka](https://babashka.org) tasks — run
`bb tasks` to list them:

| Task                | What it does                                        |
|---------------------|-----------------------------------------------------|
| `bb repl`           | dev REPL with `dev/`, `test/` and integrant loaded  |
| `bb test`           | run the test suite                                  |
| `bb test:integrant` | test suite incl. the optional integrant bindings    |
| `bb eval`           | run an eval suite (`bb eval [suite.edn [llm.edn]]`) |
| `bb lint`           | clj-kondo over `src` and `test`                     |
| `bb fmt` / `bb fmt:fix` | check / fix formatting with cljfmt             |
| `bb ci`             | format check + lint + full tests                    |
| `bb clean`          | delete `target/`                                    |
| `bb jar`            | build the library jar (tools.build)                 |
| `bb install`        | install into the local Maven repo                   |
| `bb deploy`         | deploy to Clojars                                   |

The test suite runs entirely offline: adapters are tested as pure
request-building/response-parsing functions, the tool loop and evals
against scripted in-memory adapters, and the HTTP/streaming stack
end-to-end against an in-process `com.sun.net.httpserver` standing in
for each provider.

The documentation book (rendered with
[Clay](https://scicloj.github.io/clay/) + Quarto) lives in
[`notebooks/`](notebooks/); see [`notebooks/README.md`](notebooks/README.md).

## Design notes

- Providers are *accounts/endpoints*; adapters are *wire protocols*.
  Two entries in `:assay/providers` can share an adapter (e.g. OpenAI
  and Groq), which is how one codebase talks to everything.
- The verb is `generate`, not `chat` — a zero-shot completion isn't a
  conversation, and a conversation is just `generate` over accumulated
  messages.
- Evals are data all the way down: suites are EDN, results are maps,
  and every production response is already an eval case in waiting.
- The name: an assay is a quantitative test of quality — the library is
  named for the discipline it's built around, measurement.

## License

Copyright © 2026 Kira Howe

Distributed under the MIT License.
