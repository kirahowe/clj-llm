# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added
- Initial implementation: stateless `generate` and `embed` API over
  provider-agnostic config.
- Evals as a first-class concept: every response is a complete,
  replayable interaction record (`:request`, `:usage`, `:latency-ms`,
  `:started-at`, `:op`), an `:on-interaction` hook collects records from
  live traffic, and `kirahowe.clj-llm.eval` runs suites (cases ×
  variants) with built-in scorers, custom scorers and model-graded
  `llm-judge` scoring into per-variant comparison summaries.
- EDN config files read with aero (`#env`, `#or`, `#profile`, ...).
- Adapters for Anthropic, the OpenAI chat-completions protocol (OpenAI,
  OpenRouter, Groq, Together, vLLM, LM Studio, ...) and Ollama's native API.
- Streaming via an `:on-chunk` callback (SSE and NDJSON over clj-http).
- Automatic tool-calling loop for tools defined as maps with a `:fn`.
- Model aliases (`:models` in config) so code can name intents, not vendors.
- Optional Integrant bindings (`kirahowe.clj-llm.integrant`) with
  provider `start`/`stop` lifecycle hooks.
- Babashka tasks for all dev workflows (`bb tasks`), including `bb eval`.

[Unreleased]: https://github.com/kirahowe/clj-llm/compare/...HEAD
