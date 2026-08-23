# Examples

These three programs use the same `llm/generate` call in a command-line tool, a terminal chat, and a web handler. Each one is a standalone project under [`examples/`](https://github.com/kirahowe/clj-llm/tree/main/examples).

They use [Ollama](https://ollama.com/) so you can run them without an API key. Install Ollama, pull a model, and clone this repository:

```sh
ollama pull llama3.2
git clone https://github.com/kirahowe/clj-llm.git
cd clj-llm
```

Set `OLLAMA_HOST` or `OLLAMA_MODEL` if you want to use a different server or model. The defaults are `http://localhost:11434` and `llama3.2`.

## Ask from the command line

The smallest example reads a prompt from its arguments or standard input and prints the answer:

```clojure
(ns example.ask
  (:require [clj-llm.core :as llm]
            [clojure.string :as str]))

(def config (llm/read-config "llm.edn"))

(defn ask [prompt]
  (:llm/text (llm/generate config prompt)))

(defn -main [& args]
  (let [prompt (if (seq args)
                 (str/join " " args)
                 (slurp *in*))]
    (println (ask prompt))))
```

Run it with either form:

```sh
cd examples/ask
clojure -M -m example.ask "Why is the sky blue?"
echo "Explain immutable data" | clojure -M -m example.ask
```

The `ask` function also works at the REPL:

```clojure
(require '[example.ask :as ask])
(ask/ask "What is a Clojure keyword?")
```

[View the complete one-shot project.](https://github.com/kirahowe/clj-llm/tree/main/examples/ask)

## Build a streaming terminal chat

The chat loop keeps the latest `:llm/messages` vector and passes it into the next call. The callback prints each text chunk as it arrives:

```clojure
(ns example.chat
  (:require [clj-llm.core :as llm]
            [clojure.string :as str]))

(def config (llm/read-config "llm.edn"))

(defn -main [& _]
  (println "Chat with the model. Enter :quit to stop.")
  (loop [messages []]
    (print "you> ")
    (flush)
    (when-let [prompt (read-line)]
      (when-not (= ":quit" (str/trim prompt))
        (print "assistant> ")
        (flush)
        (let [response
              (llm/generate
               config
               {:llm/messages
                (conj messages {:role :user :content prompt})}
               {:llm/on-chunk
                (fn [{:keys [type text]}]
                  (when (= :text type)
                    (print text)
                    (flush)))})]
          (println)
          (recur (:llm/messages response)))))))
```

Run it and type at the `you>` prompt. Enter `:quit` or press `Ctrl-D` to stop.

```sh
cd examples/chat
clojure -M -m example.chat
```

[View the complete terminal chat project.](https://github.com/kirahowe/clj-llm/tree/main/examples/chat)

## Put a prompt behind an HTTP endpoint

This Ring handler accepts a plain-text prompt at `POST /generate` and returns the model's answer:

```clojure
(ns example.prompt-server
  (:require [clj-llm.core :as llm]
            [ring.adapter.jetty :as jetty]))

(def config (llm/read-config "llm.edn"))

(defn handler [{:keys [request-method uri body]}]
  (if (and (= :post request-method) (= "/generate" uri))
    {:status 200
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body (:llm/text (llm/generate config (slurp body)))}
    {:status 404
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body "Not found\n"}))

(defn -main [& _]
  (println "Listening on http://localhost:3000")
  (jetty/run-jetty handler {:port 3000}))
```

Start the server:

```sh
cd examples/prompt-server
clojure -M -m example.prompt-server
```

Then call it from another terminal:

```sh
curl --data 'Give me one sentence about immutable data.' \
  http://localhost:3000/generate
```

[View the complete HTTP server project.](https://github.com/kirahowe/clj-llm/tree/main/examples/prompt-server)

## Use the examples in another project

The checked-in examples use `{:local/root "../.."}` so they run against this checkout. In your own `deps.edn`, use the released dependency instead:

```clojure
com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}
```

Copy `llm.edn` with the source file, or replace it with one of the provider configurations in [Getting started](../getting_started.qmd).
