# Roadmap

Everything here is designed to be *additive* — new keys, new options, new namespaces — because the compatibility promises in the design chapter forbid anything else. Items are roughly ordered by how much they'd improve the eval workflow.

## Evals

**Response caching.** Re-running a suite after editing only a scorer should not re-pay for every LLM call. Planned shape: a `:cache` option on `eval/run` (and possibly on `generate` itself) taking a user-supplied get/put pair keyed on the resolved request — so the cache store is yours (atom, disk, Redis) and reproducibility comes free. This matters as suites grow from ten cases to a thousand.

**EDN-expressible judges.** `llm-judge` returns a closure, so pure-EDN suite files can't use it today (code-constructed suites can). Planned: a recognized data form — `{:llm-judge {:model :smart :criteria "..." :id :grounded}}` — in `:lib/scorers`, expanded by the runner.

**Per-case weights and richer thresholds.** A `:lib/weight` on cases for weighted means; threshold forms beyond per-scorer minimums (per-variant, min-per-case, max-regression-vs-baseline).

**Report diffing.** Reports already carry provenance (`:lib/run-at`, counts, per-variant models). Planned: a helper that takes two stored reports and produces a comparison — score deltas per variant/scorer, latency and cost movement — so "did this week's model change help?" is one function call.

**Task output contract for process scoring.** System-level tasks (`:lib/task`) currently return a response-shaped map and scorers grade the final text. For grading *process* — did retrieval find the right document, how many tool rounds were taken — a reserved place for intermediate artifacts on the task's return value (e.g. `:lib/trace`) would let scorers see inside the pipeline without each project inventing its own convention.

## Core

**Multimodal content parts.** The message spec reserves vector-of-typed-parts content. Image input is the likely first part type; it arrives as new part maps and new adapter capabilities, with string content remaining valid forever.

**New chunk types.** Tool-call deltas and thinking/reasoning streams as new `:type` values on `:lib/on-chunk` payloads — the type-tag contract exists precisely so these can ship without breaking anyone.

**Retries and rate-limit handling.** Deliberately absent today (a library that silently retries is a library that silently triples your bill). If added, it will be explicit opt-in config on a provider, and `:lib/http-error` ex-data will grow (optional) retry metadata.

**Async variants.** `generate` is synchronous by design; an async entry point (CompletableFuture or callback-based) would be a new function, not a change to `generate`.

## Explicitly not planned

- **Prompt templating** — string building is Clojure's job; suites and variants already cover comparing prompts.
- **Agent frameworks** — the manual tool loop is the extension point; frameworks can build on it.
- **A client object** — statelessness is the feature.
