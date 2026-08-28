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

;; There is no chat object. A conversation is the `:llm/messages` vector in a
;; complete `generate` response:

(def first-response
  "The complete response for the first turn."
  (llm/generate config "Name a prime number between 100 and 200."))

;; Continue by passing the next prompt positionally and the prior messages in
;; opts. `generate` appends that prompt as the next user message:

(def continued-response
  "The complete response after a second turn."
  (llm/generate config
                "Why is it prime?"
                {:llm/messages (:llm/messages first-response)}))

continued-response

;; The accumulated conversation is plain data. Four messages now appear in the
;; final response—user, assistant, user, assistant:

(mapv :role (:llm/messages continued-response))

;; Store that vector where your application keeps state: a Ring session, an
;; atom, or a database row. Its schema is `clj-llm.spec/Message`. The complete
;; response remains the canonical result; read `:llm/text` when a destination
;; needs only display text.

;; A single prompt and a multi-turn conversation both use `generate`; only the
;; `:llm/messages` option changes.

;; ## Streaming

;; Pass a named callback as `:llm/on-chunk` to receive output as it is produced.
;; Each chunk has a `:type`; text deltas are
;; `{:type :text :text "delta"}`. This callback is named for its destination
;; and deliberately ignores chunk types it does not handle:

(def collected-text
  "Text deltas collected from one streamed response."
  (atom []))

(defn collect-chunk!
  "Collects a text chunk and ignores every other chunk type."
  [{:keys [type text]}]
  (when (= :text type)
    (swap! collected-text conj text)))

;; Streaming composes with continuation in the ordinary call shape. The prompt
;; stays positional, while one opts map carries both the prior conversation and
;; the named callback:

(def streamed-response
  "The complete response returned after a streamed continuation finishes."
  (llm/generate config
                "Give one practical use for that prime."
                {:llm/messages (:llm/messages continued-response)
                 :llm/on-chunk collect-chunk!}))

;; Streaming changes delivery, not the result. The callback's text concatenates
;; to the final `:llm/text`, while `streamed-response` retains all ordinary
;; response keys such as `:llm/messages`, `:llm/usage`, and `:llm/raw`:

(= (str/join @collected-text) (:llm/text streamed-response))

streamed-response

;; In a terminal, define the typed `print-chunk` callback in the same copyable
;; context. It prints and flushes only text chunks, safely ignoring unknown
;; types. `generate` still returns the canonical complete response:

(kind/code
 "(defn print-chunk
   \"Prints text chunks to the terminal and ignores other chunk types.\"
   [{:keys [type text]}]
   (when (= :text type)
     (print text)
     (flush)))

 (def response
   (llm/generate config
                 \"Tell me a story.\"
                 {:llm/on-chunk print-chunk}))

 response")

;; The three built-in adapters return the same chunk shape. Anthropic and
;; OpenAI-compatible servers use server-sent events, while Ollama uses
;; newline-delimited JSON; the adapter handles that difference.
