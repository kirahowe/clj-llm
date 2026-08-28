# Examples

These three programs use the same `llm/generate` call in a command-line tool, a
terminal chat, and a web handler. Each one is a standalone project under
[`examples/`](https://github.com/kirahowe/clj-llm/tree/main/examples).

They use [Ollama](https://ollama.com/) so you can run them without an API key.
Install Ollama, then pull the model and verify both the model inventory and the
local service:

```sh
ollama pull llama3.2
ollama list
curl -fsS http://localhost:11434/api/version
```

Clone the repository, then use its launcher from any working directory:

```sh
git clone https://github.com/kirahowe/clj-llm.git
/absolute/path/to/clj-llm/examples/run ask "Why is the sky blue?"
/absolute/path/to/clj-llm/examples/run chat
/absolute/path/to/clj-llm/examples/run prompt-server
```

From the repository root, the same commands begin with `./examples/run`. The
launcher resolves its own directory and changes internally only to select the
chosen standalone project's `deps.edn`. The ask configuration stays inline;
the chat and server configurations stay on their project classpaths. None of
those config lookups depends on the caller's working directory.

## Ask from the command line

The smallest example reads a prompt from its arguments or standard input and
prints the answer. Its reusable `ask` function accepts configuration explicitly;
`-main` passes the namespace's private immutable Ollama configuration:

```clojure
(ns example.ask
  "Sends one prompt to an explicitly configured language model."
  (:require [clj-llm.core :as llm]
            [clojure.string :as str]))

(def ^:private config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(defn ask
  "Returns the generated text for `prompt` using `config`."
  [config prompt]
  (:llm/text (llm/generate config prompt)))

(defn -main
  "Reads a prompt from command-line arguments or standard input and prints its answer."
  [& args]
  (let [prompt (if (seq args)
                 (str/join " " args)
                 (slurp *in*))]
    (println (ask config prompt))))
```

Run it with either input form from any working directory:

```sh
/absolute/path/to/clj-llm/examples/run ask "Why is the sky blue?"
echo "Explain immutable data" | /absolute/path/to/clj-llm/examples/run ask
```

The launcher changes internally into `examples/ask` only to select its
`deps.edn`; the namespace's immutable configuration remains inline.

The `ask` function also works at the REPL when you supply its configuration:

```clojure
(require '[example.ask :as ask])

(def config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(ask/ask config "What is a Clojure keyword?")
```

[View the complete one-shot project.](https://github.com/kirahowe/clj-llm/tree/main/examples/ask)

## Build a streaming terminal chat

The chat resolves `example/chat/llm.edn` from the classpath once at startup. It
keeps the latest complete `:llm/messages` vector and passes it in the options map
with a named streaming callback, while each new prompt remains positional:

```clojure
(ns example.chat
  "A streaming terminal chat example."
  (:require [clj-llm.core :as llm]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- print-chunk
  "Print a streamed text chunk."
  [{:keys [type text]}]
  (when (= :text type)
    (print text)
    (flush)))

(defn -main
  "Start a terminal chat, loading its classpath config once."
  [& _]
  (let [resource-name "example/chat/llm.edn"
        resource (or (io/resource resource-name)
                     (throw
                      (ex-info
                       (str "Chat config resource not found on classpath: "
                            resource-name)
                       {:type :llm/config-not-found
                        :resource resource-name})))
        config (llm/read-config resource)]
    (println "Chat with the model. Enter :quit to stop.")
    (loop [messages []]
      (print "you> ")
      (flush)
      (when-let [prompt (read-line)]
        (when-not (= ":quit" (str/trim prompt))
          (print "assistant> ")
          (flush)
          (let [response (llm/generate
                          config
                          prompt
                          {:llm/messages messages
                           :llm/on-chunk print-chunk})]
            (println)
            (recur (:llm/messages response))))))))
```

Run it from any working directory and type at the `you>` prompt. Enter `:quit`
or press `Ctrl-D` to stop:

```sh
/absolute/path/to/clj-llm/examples/run chat
```

The launcher changes internally into `examples/chat` to select its `deps.edn`,
which places `resources/example/chat/llm.edn` on the classpath. The startup
lookup is a classpath resource lookup, not a filesystem search relative to the
caller or launcher working directory. Requiring the namespace performs no config
I/O. `print-chunk` handles only typed `:text` events; streaming still returns
the complete response whose `:llm/messages` become the next turn's history.

[View the complete terminal chat project.](https://github.com/kirahowe/clj-llm/tree/main/examples/chat)

## Put a prompt behind an HTTP endpoint

This local-only Ring handler accepts a plain-text prompt of up to 65,536 bytes
at `POST /generate` and returns the model's answer. `handler` accepts parsed
configuration and returns a Ring handler; `-main` loads the classpath resource
once before starting Jetty:

```clojure
(ns example.prompt-server
  "A loopback-only HTTP prompt server example."
  (:require [clj-llm.core :as llm]
            [clojure.java.io :as io]
            [ring.adapter.jetty :as jetty])
  (:import (java.nio.charset StandardCharsets)))

(def ^:private config-resource "example/prompt-server/llm.edn")
(def ^:private max-prompt-bytes 65536)

(defn- generate-response!
  [config body]
  (let [bytes (.readNBytes body (inc max-prompt-bytes))]
    (if (> (alength bytes) max-prompt-bytes)
      {:status 413
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body "Prompt too large\n"}
      {:status 200
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body (:llm/text
              (llm/generate config
                            (String. bytes StandardCharsets/UTF_8)))})))

(defn handler
  "Return a Ring handler that generates responses using `config`."
  [config]
  (fn [{:keys [request-method uri body]}]
    (if (and (= :post request-method) (= "/generate" uri))
      (generate-response! config body)
      {:status 404
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body "Not found\n"})))

(defn -main
  "Load the example config and start the loopback-only HTTP server."
  [& _]
  (let [resource (or (io/resource config-resource)
                     (throw
                      (ex-info
                       (str "Config resource not found on classpath: "
                            config-resource)
                       {:type :llm/config-not-found
                        :resource config-resource})))
        config (llm/read-config resource)]
    (println "Listening on http://127.0.0.1:3000")
    (jetty/run-jetty (handler config)
                     {:host "127.0.0.1" :port 3000})))
```

Start the standalone server from any working directory:

```sh
/absolute/path/to/clj-llm/examples/run prompt-server
```

The launcher changes internally into `examples/prompt-server` to select its
`deps.edn` and place `resources/example/prompt-server/llm.edn` on the
classpath. `-main` resolves and reads that startup resource before opening a
port; config loading is not relative to the caller or launcher working directory.
Requiring the namespace performs no config I/O, and `handler` itself receives
configuration explicitly.

Then call it from another terminal:

```sh
curl --data 'Give me one sentence about immutable data.' \
  http://127.0.0.1:3000/generate
```

This example is hard-coded to loopback and is not a production service or
deployment template. Its body limit does not replace authentication,
authorization, rate and cost controls, bounded concurrency, end-to-end
deadlines, safe logging and secret handling, or deployment hardening. A reverse
proxy alone does not make the handler safe to expose.

[View the complete HTTP server project.](https://github.com/kirahowe/clj-llm/tree/main/examples/prompt-server)

## Evaluate the repository checkout

The checked-in examples use `{:local/root "../.."}` so they run against the
current checkout. They are for repository evaluation only. This alpha currently
has no published or tagged immutable consumer coordinate; consumer installation
guidance will become available after a release tag and a clean Clojars round
trip.
