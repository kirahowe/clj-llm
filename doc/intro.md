# Introduction to clj-llm

See the [README](../README.md) for installation, configuration and usage.

A quick orientation to the namespaces:

| Namespace                          | Role                                          |
|------------------------------------|-----------------------------------------------|
| `kirahowe.clj-llm`                 | public API: `generate`, `embed`, `read-config`|
| `kirahowe.clj-llm.config`          | EDN config loading, model/provider resolution |
| `kirahowe.clj-llm.provider`        | the adapter contract (multimethods)           |
| `kirahowe.clj-llm.http`            | JSON + SSE/NDJSON transport over java.net.http|
| `kirahowe.clj-llm.providers.*`     | built-in adapters: anthropic, openai, ollama  |
| `kirahowe.clj-llm.eval`            | eval suites: cases × variants → scored report |
| `kirahowe.clj-llm.integrant`       | optional Integrant bindings                   |
