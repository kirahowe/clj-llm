# Introduction to clj-llm

See the [README](../README.md) for installation, configuration and
usage, and the [documentation book](../notebooks/README.md) for the full
guide.

A quick orientation to the namespaces:

| Namespace           | Role                                              |
|---------------------|---------------------------------------------------|
| `clj-llm.core`        | public API: `generate`, `embed`, `read-config`    |
| `clj-llm.config`      | EDN config loading, model/provider resolution     |
| `clj-llm.provider`    | the adapter contract (multimethods) and its compatibility rules |
| `clj-llm.spec`        | malli schemas for every public data contract      |
| `clj-llm.http`        | JSON + SSE/NDJSON transport over java.net.http    |
| `clj-llm.providers.*` | built-in adapters: anthropic, openai, ollama      |
| `clj-llm.eval`        | eval suites: cases × variants → scored report     |
| `clj-llm.integrant`   | optional Integrant bindings                       |
