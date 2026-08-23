# Prompt HTTP server

This example puts the same one-shot `llm/generate` call behind a small Ring
handler. `POST /generate` accepts the raw request body and returns plain text.

Prerequisites:

- Clojure CLI
- [Ollama](https://ollama.com/) running with a model pulled, for example
  `ollama pull llama3.2`

Run it from this directory:

```bash
clojure -M -m example.prompt-server
```

In another terminal:

```bash
curl --data 'Give me one sentence about immutable data.' \
  http://localhost:3000/generate
```

Set `OLLAMA_HOST` or `OLLAMA_MODEL` to use a different Ollama endpoint or
model. The `handler` itself is ordinary Ring data: the request becomes a
prompt, and the response is a Ring response map.
