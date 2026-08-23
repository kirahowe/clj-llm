# Roadmap

These are possible additions after the alpha. They are not part of the current API.

## Evals

**Response caching.** Reuse model responses when only scorers changed. A future `:cache` option could accept user-provided read and write functions, leaving storage in the application.

**LLM judges in EDN.** `llm-judge` returns a function, so it cannot be written directly in an EDN suite today. A future scorer form could describe the judge as data.

**Case weights and more thresholds.** Add weighted means, thresholds for individual variants or cases, and limits on regression from a baseline.

**Report comparison.** Compare two stored reports by score, latency, and token use.

**Task traces.** Scorers receive the model interactions made by a task, but not steps such as retrieval or ranking. A standard trace value could expose those steps to scorers.

## Core

**Multimodal messages.** Add supported content parts for images and later other media. String content will remain valid.

**New stream chunk types.** Add tool-call deltas and any supported provider reasoning output as new `:type` values.

**Retries and rate limits.** Retries are not automatic today because they can increase cost. Any future retry behavior will require explicit configuration.

**Asynchronous calls.** `generate` is synchronous. Asynchronous support would use a new function.

## Explicitly not planned

- **Prompt templating.** Build prompt strings with ordinary Clojure functions.
- **An agent framework.** Applications and other libraries can build on manual tool handling.
- **A client object.** Public operations will continue to accept config maps.
