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

;; There is no chat object hiding mutable history. A conversation is the `:llm/messages` vector in a `generate` response:

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

;; Store that vector wherever your application already keeps state: a Ring session, an atom, or a database row. Its schema is `clj-llm.spec/Message`. Read `:llm/text` when you only need to display the answer; keep the full response when you need the conversation or call record.

;; A single prompt and a multi-turn conversation both use `generate`; only the
;; `:llm/messages` option changes.

;; ## Streaming

;; Pass a callback as `:llm/on-chunk` to receive output as it is produced. Each chunk has a `:type`; text deltas are `{:type :text :text "delta"}`. Name the callback for what it does and ignore chunk types it does not handle:

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

;; Streaming changes delivery, not the result. The callback's text concatenates to the final `:llm/text`, and `streamed-response` still contains ordinary response keys such as `:llm/messages`, `:llm/usage`, and `:llm/raw`:

(= (str/join @collected-text) (:llm/text streamed-response))

streamed-response

;; In a terminal, the callback can simply print and flush text chunks. `generate` still returns the complete response:

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
