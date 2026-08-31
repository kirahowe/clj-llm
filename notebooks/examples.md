# Examples

Want to see the library in a real program instead of another isolated snippet?
This repository includes a command-line prompt, a terminal chat, and a small
Ring handler. They are deliberately plain. Each one shows the minimum code for
its job and uses the same `llm/generate` function.

All three use [Ollama](https://ollama.com/), so you can run them without an API
key. Install Ollama and pull the model once:

```sh
ollama pull llama3.2
```

Then clone the repository and run whichever example you want:

```sh
git clone https://github.com/kirahowe/clj-llm.git
cd clj-llm
./examples/run ask "Why is the sky blue?"
./examples/run chat
./examples/run prompt-server
```

Each example is a standalone Clojure project. The small `examples/run` launcher
chooses the right `deps.edn` for you.

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

Run it with command-line arguments or pipe a prompt over standard input:

```sh
./examples/run ask "Why is the sky blue?"
echo "Explain immutable data" | ./examples/run ask
```

The useful part is not `-main`; it is the two-line `ask` function. It accepts
configuration explicitly, so it is just as easy to use from a REPL or another
namespace:

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

Run it and type at the `you>` prompt. Enter `:quit` or press `Ctrl-D` to stop:

```sh
./examples/run chat
```

The config lives at `resources/example/chat/llm.edn` and is loaded once when the
program starts. Requiring the namespace does not read config or make a network
call. `print-chunk` handles only typed `:text` events; streaming still returns
the complete response, and its `:llm/messages` become the next turn's history.

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

Start the server:

```sh
./examples/run prompt-server
```

Then call it from another terminal:

```sh
curl --data 'Give me one sentence about immutable data.' \
  http://127.0.0.1:3000/generate
```

This is a local example, not a deployment template. It binds to loopback and
limits prompt bodies to 64 KiB. A public LLM endpoint also needs authentication,
authorization, rate and cost controls, bounded concurrency, deadlines, safe
logging, secret handling, and the usual deployment hardening.

[View the complete HTTP server project.](https://github.com/kirahowe/clj-llm/tree/main/examples/prompt-server)

## Use these in your own project

The checked-in examples use `{:local/root "../.."}` on purpose, so they always
run against the source beside them. In an application, replace that with the
released coordinate:

```clojure
com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}
```

From there, take the useful function and leave the demo scaffolding behind. The
examples are small enough that you should not need to adopt an application
structure just to borrow one idea.
