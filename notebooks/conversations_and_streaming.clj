;; # Conversations and streaming

(ns conversations-and-streaming
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]
            [clojure.string :as str]
            [scicloj.kindly.v4.kind :as kind]))

(def config demo/config)

;; ## Multi-turn is just data

;; There is no chat object. A conversation is the `:clj-llm/messages` vector, and continuing one means conj-ing the next user message onto the messages of the previous response:

(def r1 (llm/generate config "Name a prime number between 100 and 200."))

(:clj-llm/text r1)

(def r2 (llm/generate config
                      {:clj-llm/messages (conj (:clj-llm/messages r1)
                                               {:role :user :content "Why is it prime?"})}))

(:clj-llm/text r2)

;; The accumulated conversation is plain data — four messages now, each a `{:role ... :content ...}` map:

(mapv :role (:clj-llm/messages r2))

;; Store that vector wherever your context keeps state: a Ring session, an atom, a database row. The library doesn't care, and because message maps are part of the frozen contract (`clj-llm.spec/Message`), conversations you persist today stay readable by every future version of the library. This also means a "conversation store" is any collection of message vectors — there is nothing to integrate with.

;; A zero-shot call and a conversation are the same operation; the verb is `generate`, not `chat`, because a one-off completion shouldn't have to pretend to be a dialogue.

;; ## Streaming

;; Pass `:clj-llm/on-chunk` to receive output as it is produced. Each chunk is a map with a `:type` — text deltas are `{:type :text :text "delta"}`:

(def chunks (atom []))

(def streamed
  (llm/generate config "Tell me a story."
                {:clj-llm/on-chunk (fn [{:keys [type text]}]
                                     (when (= :text type)
                                       (swap! chunks conj text)))}))

@chunks

;; The chunks concatenate to exactly the final text, and the complete response map is still returned at the end — streaming changes delivery, not the result:

(= (str/join @chunks) (:clj-llm/text streamed))

;; That `(when (= :text type) ...)` guard is not decoration — it is the forward-compatibility contract. Future versions may stream other chunk types (tool-call deltas, thinking, round boundaries in the tool loop), and they will arrive as new `:type` values. A callback that ignores types it doesn't recognize keeps working forever; a callback that assumes every chunk has text does not. Write the guard.

;; In a terminal you'd print instead of collecting:

(kind/code
 "(llm/generate config \"Tell me a story.\"
                 {:clj-llm/on-chunk (fn [{:keys [type text]}]
                                    (when (= :text type)
                                      (print text) (flush)))})")

;; Streaming works the same across all three built-in adapters — Anthropic and OpenAI-compatible servers stream server-sent events, Ollama streams newline-delimited JSON, and the adapter normalizes both into the same chunk maps, so your callback never knows the difference.
