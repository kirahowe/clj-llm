# One-shot prompt

This is the smallest example: a prompt goes in and `:llm/text` comes out.
It uses a minimal Ollama configuration embedded in the namespace.

Prerequisites:

- Clojure CLI
- [Ollama](https://ollama.com/)

Before generating, pull the configured model, inspect the installed models, and
confirm that the Ollama server is reachable:

```bash
ollama pull llama3.2
ollama list
curl -fsS http://localhost:11434/api/version
```

Run it through the repository launcher. An absolute launcher path works from any
working directory:

```bash
/absolute/path/to/clj-llm/examples/run ask "Why is the sky blue?"
echo "Explain immutable data" | /absolute/path/to/clj-llm/examples/run ask
```

From the repository root, the shorter `./examples/run ask ...` form works too.
The launcher resolves its own directory, changes internally into `examples/ask`
only so Clojure CLI selects that standalone project's `deps.edn`, and then
executes `example.ask`. The Ollama configuration is inline and does not depend
on either the caller's or the launcher's working directory.

The same `ask` function is available at the REPL. It accepts configuration
explicitly, so requiring the namespace only defines functions and data; it
does not read files, contact Ollama, or generate text.

From the `examples/ask` directory, start `clojure -M`:

```bash
clojure -M
```

Then evaluate:

```clojure
(require '[example.ask :as ask])

(def config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(ask/ask config "What is a Clojure keyword?")
```

Calling `ask` sends the prompt with the supplied configuration and returns
`:llm/text` from the full generation result. `-main` uses the same minimal
inline configuration, joins command-line arguments into a prompt (or reads
standard input when there are none), calls `ask`, and prints the answer.
