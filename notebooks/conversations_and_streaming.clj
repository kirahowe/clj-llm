;; # Conversations and streaming

^{:kindly/hide-code true}
(ns conversations-and-streaming
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]
            [clojure.string :as str]
            [scicloj.kindly.v4.kind :as kind]))

^{:kindly/hide-code true}
(def config demo/config)

;; ## Multi-turn is just data

;; There is no chat object. A conversation is the `:llm/messages` vector, and continuing one means conj-ing the next user message onto the messages of the previous response:

(def r1 (llm/generate config "Name a prime number between 100 and 200."))

(:llm/text r1)

(def r2 (llm/generate config
                      {:llm/messages (conj (:llm/messages r1)
                                           {:role :user :content "Why is it prime?"})}))

(:llm/text r2)

;; `:llm/prompt` next to `:llm/messages` is the same thing with less typing — it appends the prompt as the next user message:

(:llm/text (llm/generate config {:llm/messages (:llm/messages r1)
                                 :llm/prompt "Why is it prime?"}))

;; The accumulated conversation is plain data. Four messages now, each a `{:role ... :content ...}` map:

(mapv :role (:llm/messages r2))

;; Store the vector where your application keeps state: a Ring session, an atom, or a database row. Its schema is `clj-llm.spec/Message`.

;; A single prompt and a multi-turn conversation both use `generate`; only the request data changes.

;; ## Streaming

;; Pass `:llm/on-chunk` to receive output as it is produced. Each chunk is a map with a `:type`, and text deltas are `{:type :text :text "delta"}`:

(def chunks (atom []))

(def streamed
  (llm/generate config "Tell me a story."
                {:llm/on-chunk (fn [{:keys [type text]}]
                                 (when (= :text type)
                                   (swap! chunks conj text)))}))

@chunks

;; The chunks concatenate to exactly the final text, and the complete response map is still returned at the end. Streaming changes delivery, not the result:

(= (str/join @chunks) (:llm/text streamed))

;; Keep the `(when (= :text type) ...)` check. Future versions may add other chunk types, and callbacks should ignore types they do not handle.

;; In a terminal you'd print instead of collecting:

(kind/code
 "(llm/generate config \"Tell me a story.\"
                 {:llm/on-chunk (fn [{:keys [type text]}]
                                    (when (= :text type)
                                      (print text) (flush)))})")

;; The three built-in adapters return the same chunk shape. Anthropic and OpenAI-compatible servers use server-sent events, while Ollama uses newline-delimited JSON; the adapter handles that difference.
