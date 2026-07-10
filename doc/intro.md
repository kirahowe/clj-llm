# Introduction to assay

See the [README](../README.md) for installation, configuration and
usage, and the [documentation book](../notebooks/README.md) for the full
guide.

A quick orientation to the namespaces:

| Namespace           | Role                                              |
|---------------------|---------------------------------------------------|
| `assay.core`        | public API: `generate`, `embed`, `read-config`    |
| `assay.config`      | EDN config loading, model/provider resolution     |
| `assay.provider`    | the adapter contract (multimethods) and its compatibility rules |
| `assay.spec`        | malli schemas for every public data contract      |
| `assay.http`        | JSON + SSE/NDJSON transport over java.net.http    |
| `assay.providers.*` | built-in adapters: anthropic, openai, ollama      |
| `assay.eval`        | eval suites: cases × variants → scored report     |
| `assay.integrant`   | optional Integrant bindings                       |
