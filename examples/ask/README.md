# One-shot prompt

This is the smallest example: a prompt goes in and `:llm/text` comes out.

Prerequisites:

- Clojure CLI
- [Ollama](https://ollama.com/) running with a model pulled, for example
  `ollama pull llama3.2`

Run it from this directory:

```bash
clojure -M -m example.ask "Why is the sky blue?"
echo "Explain immutable data" | clojure -M -m example.ask
```

The same `ask` function is available at the REPL:

From the `examples/ask` directory, start `clojure -M`:

```bash
clojure -M
```

Then evaluate:

```clojure
(require '[example.ask :as ask])
(ask/ask "What is a Clojure keyword?")
```

Set `OLLAMA_HOST` or `OLLAMA_MODEL` to use a different Ollama endpoint or
model. The defaults are `http://localhost:11434` and `llama3.2`. `ask`
extracts `:llm/text`; `-main` chooses command-line arguments or stdin.
