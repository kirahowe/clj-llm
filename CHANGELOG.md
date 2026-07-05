# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added
- Initial implementation: stateless `generate` and `embed` API over
  provider-agnostic config.
- EDN config loading with `#env`, `#or` and `#profile` reader tags.
- Adapters for Anthropic, the OpenAI chat-completions protocol (OpenAI,
  OpenRouter, Groq, Together, vLLM, LM Studio, ...) and Ollama's native API.
- Streaming via an `:on-chunk` callback (SSE and NDJSON transports).
- Automatic tool-calling loop for tools defined as maps with a `:fn`.
- Model aliases (`:models` in config) so code can name intents, not vendors.
- Optional Integrant bindings (`kirahowe.clj-llm.integrant`) with
  provider `start`/`stop` lifecycle hooks.
- Babashka tasks for all dev workflows (`bb tasks`).

[Unreleased]: https://github.com/kirahowe/clj-llm/compare/...HEAD
