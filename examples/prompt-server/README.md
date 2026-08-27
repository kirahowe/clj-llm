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

Run the isolated example tests without Ollama or an open port:

```bash
clojure -M:test
```

The server is hard-coded to bind to `127.0.0.1`; it is local demonstration
code, not a production service or deployment template. It has no
authentication, authorization, rate or cost controls, bounded concurrency,
end-to-end request deadlines, safe logging policy, secret management, or
deployment hardening. The prompt body limit is its only request guardrail.
A reverse proxy alone does not supply these missing security boundaries or
make this handler safe to expose.
