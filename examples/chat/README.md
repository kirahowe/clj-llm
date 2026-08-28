# Streaming terminal chat

This example keeps the complete conversation history returned by
`llm/generate` and prints each text chunk as it arrives.

## Prerequisites

- Clojure CLI
- [Ollama](https://ollama.com/) installed locally

Install Ollama. Its desktop application or your service manager normally starts
the daemon; run `ollama serve` in a separate terminal only if neither has
started it. Then pull the model and verify both the model inventory and local
service, in this order:

```bash
ollama pull llama3.2
ollama list
curl -fsS http://localhost:11434/api/version
```

## Run

Run the standalone project through the repository launcher. An absolute
launcher path works from any working directory:

```bash
/absolute/path/to/clj-llm/examples/run chat
```

From the repository root, `./examples/run chat` is equivalent. The launcher
resolves its own directory and changes internally into `examples/chat` only so
Clojure CLI selects `examples/chat/deps.edn`.

Type prompts at `you>`. Enter `:quit` or send EOF (`Ctrl-D`) to stop.

Run the isolated example tests from `examples/chat`; they do not contact Ollama
or any other provider:

```bash
clojure -M:test
```

## Configuration lifecycle

The canonical config is the classpath resource
`resources/example/chat/llm.edn`:

```clojure
#:llm{:providers {:ollama {:llm/adapter :ollama}}
      :defaults #:llm{:model "ollama/llama3.2"}}
```

Requiring `example.chat` performs no config I/O. At startup, `-main` resolves
`example/chat/llm.edn` from the classpath, reports an actionable error if it is
missing, and calls `llm/read-config` once. The launcher's internal directory
change selects the standalone project and constructs that classpath; config
lookup is not a filesystem lookup relative to either working directory. The
resulting config remains local to the running chat.

## Conversation flow

The basic call passes a config and a prompt:

```clojure
(def response (llm/generate config "Why is the sky blue?"))
```

Continue from the complete message history in that response:

```clojure
(def next-response
  (llm/generate config
                "Can you summarize that?"
                {:llm/messages (:llm/messages response)}))
```

The terminal chat adds its named callback to the same options map on every
turn:

```clojure
(llm/generate config
              prompt
              {:llm/messages messages
               :llm/on-chunk print-chunk})
```

`print-chunk` handles only `:text` events, printing and flushing their text.
The callback does not replace the return value: `llm/generate` still returns
the full response map. The chat retains `:llm/messages` from that complete
return and passes it into the next turn. There is no separate chat or session
object.
