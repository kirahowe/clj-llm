# Prompt HTTP server

This local-only demonstration puts the same one-shot `llm/generate` call behind
a small Ring handler. `POST /generate` accepts a raw UTF-8 request body of up to
65,536 bytes and returns plain text.

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
  http://127.0.0.1:3000/generate
```

Set `OLLAMA_HOST` or `OLLAMA_MODEL` to use a different Ollama endpoint or
model. The `handler` itself is ordinary Ring data: the request becomes a
prompt, and the response is a Ring response map.

The server binds explicitly to `127.0.0.1`; it is not exposed to other hosts.
This is a minimal demonstration, not a production service: it has only a prompt
body size limit and does not provide authentication, authorization, rate
limits, request timeouts, or other deployment hardening.
