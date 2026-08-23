# Streaming terminal chat

This example keeps the conversation as the response's `:llm/messages` and
prints each text chunk as it arrives.

Prerequisites:

- Clojure CLI
- [Ollama](https://ollama.com/) running with a model pulled, for example
  `ollama pull llama3.2`

Run it from this directory:

```bash
clojure -M -m example.chat
```

Type prompts at `you>`. Enter `:quit` or send EOF (`Ctrl-D`) to stop. Set
`OLLAMA_HOST` or `OLLAMA_MODEL` to use a different Ollama endpoint or model.

There is no chat object here: each turn passes the accumulated message vector
back to `llm/generate`. The `:llm/on-chunk` callback handles streaming, while
the complete response still supplies the next `:llm/messages` value.
