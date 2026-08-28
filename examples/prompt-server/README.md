# Prompt HTTP server

This local-only demonstration puts the same one-shot `llm/generate` call behind
a small Ring handler. `POST /generate` accepts a raw UTF-8 request body of up to
65,536 bytes and returns plain text.

Before starting the server, pull the configured model and verify both the model
inventory and the local Ollama service:

```bash
ollama pull llama3.2
ollama list
curl -fsS http://localhost:11434/api/version
```

If the final command cannot connect, start Ollama through its desktop
application or with `ollama serve`, then repeat the preflight. Do not start
`ollama serve` when an Ollama service is already running.

The example also requires the Clojure CLI. Run it through the repository
launcher; an absolute launcher path works from any working directory:

```bash
/absolute/path/to/clj-llm/examples/run prompt-server
```

From the repository root, `./examples/run prompt-server` is equivalent.

In another terminal:

```bash
curl --data 'Give me one sentence about immutable data.' \
  http://127.0.0.1:3000/generate
```

The launcher resolves its own directory and changes internally into
`examples/prompt-server` only so Clojure CLI selects the project whose `:paths`
put `src` and `resources` on the classpath. Config loading is not relative to
either working directory. The config file is
`resources/example/prompt-server/llm.edn`, and `-main` resolves it by its
classpath resource name, `example/prompt-server/llm.edn`.

`-main` reads that resource once at startup, before Jetty is launched, and
reports a clear error if it is absent. Requiring the namespace does not read
config. Restart the process after changing the resource so the new config is
loaded.

`handler` accepts the parsed config explicitly and returns an ordinary Ring
handler. Keeping config loading and Jetty startup outside the handler makes the
request behavior testable without config I/O, Ollama, or an open port.

From any working directory, run the five isolated prompt-server tests without
Ollama or an open port:

```bash
(cd /absolute/path/to/clj-llm/examples/prompt-server && clojure -M:test)
```

Changing into the example project ensures Clojure selects its `:test` alias;
this command does not run the repository root test suite.

The server is hard-coded to bind to `127.0.0.1`; it is local demonstration
code, not a production service or deployment template. It has no
authentication, authorization, rate or cost controls, bounded concurrency,
end-to-end request deadlines, safe logging policy, secret management, or
deployment hardening. The prompt body limit is its only request guardrail.
A reverse proxy alone does not supply these missing security boundaries or
make this handler safe to expose.
