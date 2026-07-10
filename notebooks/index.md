# clj-llm

A small, functional Clojure library for calling large language models — any model, from any provider, including local ones — with evals built in from the ground up.

The premise: you can't build well with LLMs unless measuring what they do is as easy as calling them. Most LLM libraries treat evaluation as someone else's problem; here every response is already a replayable measurement record, and turning a folder of real interactions into a scored comparison of models, prompts, or whole pipelines is a one-liner.

## What it looks like

```clojure
(require '[clj-llm.core :as llm])

(def config (llm/read-config "llm.edn"))

(llm/generate config "Why is the sky blue?")
;; => #:lib{:text "Sunlight scattering..." :usage {...} :latency-ms 640 ...}
```

And the part the library is built around:

```clojure
(require '[clj-llm.eval :as eval])

(eval/print-summary (eval/run config "evals/suite.edn"))
;; variant    model              cases  errors  includes  latency(mean ms)  in-tok  out-tok
;; ---------  -----------------  -----  ------  --------  ----------------  ------  -------
;; :baseline  claude-sonnet-4-6  3      0       1.000     642               118     57
;; :cheap     llama-3.3-70b      3      0       0.667     97                118     41
```

## Principles

- **Stateless and functional.** No client objects, no sessions, no global state. Every function takes a config map and returns data, so the same calls work in a web handler, a CLI, a background job, or the REPL.
- **Config is data.** Providers, model aliases and defaults live in an EDN file read with [aero](https://github.com/juxt/aero); API keys come from the environment via `#env`. Code names intents (`:smart`, `:fast`); config decides what they mean.
- **Conversations are data.** A conversation is a vector of message maps; multi-turn means passing the previous messages back in.
- **Evals are first class.** Suites are EDN, scorers are functions, reports are maps, thresholds can gate CI — and the thing under test can be a single call or your whole system.
- **One protocol away from any provider.** Adapters are multimethods; the OpenAI-compatible adapter alone covers most of the hosted and self-hosted ecosystem.
- **Compatible forever.** The keyspace is partitioned so your keys can never collide with the library's, every contract has a malli schema, and the compatibility promises are documented in the [design chapter](design.html).

## Installation

Not yet on Clojars. Use it as a git dependency:

```clojure
;; deps.edn
{:deps {com.kirahowe/clj-llm {:git/url "https://github.com/kirahowe/clj-llm"
                            :git/sha "..."}}}
```

Dependencies are deliberately light: [aero](https://github.com/juxt/aero), [charred](https://github.com/cnuernber/charred) and [malli](https://github.com/metosin/malli); HTTP uses the JDK's built-in `java.net.http` client.

## One rule before the examples

Every key the library defines in maps you author or store — config, requests, responses, eval suites, reports — is namespaced `:lib/...`. Any other key in those maps is yours, forever. Conversation-shaped structures (messages, tool definitions, tool calls, usage, stream chunks, scores) keep their plain industry-standard keys (`:role`, `:content`, `:score`, ...), and *that* plain keyspace is reserved by the library. Clojure's namespaced-map literal keeps the qualified form light: `#:lib{:prompt "hi" :model :fast}`.

## How this book runs

Every code example in the `.clj` chapters is evaluated when the book is rendered. To keep that deterministic and offline, the examples run against a canned in-process adapter (`book.demo`) whose config is shaped exactly like a real one — swap in `(llm/read-config "llm.edn")` and the same code talks to real providers.
