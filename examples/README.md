# Examples

These examples use the same small `clj-llm` API in three settings. Each
directory is a standalone project and expects Ollama to be running locally.

1. [ask](ask/) — one prompt from the command line, or one function call at
   the REPL.
2. [chat](chat/) — a streaming, multi-turn terminal conversation.
3. [prompt-server](prompt-server/) — a tiny Ring/Jetty HTTP endpoint.

They are intentionally small. Read them in order to see the same
`llm/generate` call move from a one-off expression, to a loop, to an HTTP
handler. Each `deps.edn` uses `:local/root "../.."` only because the example
runs inside this repository checkout; that relative path resolves the checkout
on disk. Do not copy it into an unrelated project. Use the
[`0.1.0-alpha1` coordinate](../README.md#installation) in an application instead.
