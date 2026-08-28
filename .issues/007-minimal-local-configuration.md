# Lead onboarding with the minimal local configuration

- **ID:** ONB-007
- **Status:** Resolved
- **Priority:** P0 onboarding
- **Alpha disposition:** Completed for the next alpha documentation

## Problem

At issue creation, the first-success path taught an Aero file, an Ollama
provider, a model alias, a default alias, and environment fallbacks before the
first call. Although those are useful application features, that ordering made
configuration appear more complex than the public schema requires and
introduced file-path failure before a user could exercise `generate`.

A valid minimal inline Ollama config existed within the API then and remains
valid. The onboarding problem did not require a constructor, implicit
provider/model selection, or another configuration language.

## Evidence at issue creation

- `src/clj_llm/spec.clj:105-116` requires only `:llm/providers`; both
  `:llm/models` and `:llm/defaults` are optional. `ModelDesignator` accepts a
  `"provider/model"` string at lines 94-97.
- `src/clj_llm/providers/ollama.clj:134-135` already defaults the Ollama base
  URL to `http://localhost:11434`.
- `src/clj_llm/config.clj:31-40` states that inline maps and Aero/Integrant
  results are equivalent to the rest of the library.
- `README.md:18-29` led with `llm.edn`, `#or`, `#env`, a named model alias,
  and a default model alias.
- `resources/clj-llm/config.example.edn` and
  `notebooks/getting_started.clj` present multi-model/Aero features valuable as
  later reference material, not prerequisites for the first response.

## Implementation evidence — 2026-08-27

- `README.md`, `notebooks/index.md`, and `notebooks/getting_started.clj`
  now lead with the same two-layer inline Ollama map and defer file-backed
  configuration, Aero, aliases, multiple providers, and Integrant until after
  the first generation path.
- The checkout launch commands in `README.md` and `notebooks/index.md`
  select the repository's Clojure classpath; that development concern is
  separate from configuration lookup. The first-success configuration is inline
  and therefore independent of the process working directory.

## Final verification — 2026-08-27

- The root `bb ci` gate passed with 101 tests and 564 assertions, zero
  failures or errors, and clean formatting and clj-kondo checks. The chat suite
  passed with 2 tests and 5 assertions, and the prompt-server suite passed with
  5 tests and 13 assertions.
- The offline book render succeeded with deliberately invalid `OLLAMA_HOST` and
  `OLLAMA_MODEL`, confirming that rendering the onboarding material does not
  require environment-provided Ollama configuration.
- From `/tmp`, the checkout's `examples/run` launcher successfully ran the
  ask, chat, and prompt-server examples: ask and prompt-server returned `OK`,
  while chat loaded and exited normally. This verifies the launcher's explicit
  checkout classpath selection; it is distinct from the first-success snippet's
  working-directory independence, which comes from carrying its configuration
  inline rather than looking up a config file.
- A focused smoke copied only the documented inline Ollama setup and first call;
  an installed `llama3.2` model returned `OK` through Ollama 0.32.5. Real
  classpath resources also resolved and parsed, and the README's explicit
  `nomic-embed-text` example returned 768 dimensions, preserving the later
  advanced configuration paths.
- Two sequential clean adversarial reviews completed with no findings.

## Design work

Make this already-valid map the first local configuration shown in the root
quickstart and the beginning of the getting-started guide:

```clojure
(def config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})
```

Explain its two layers directly: `:llm/providers` registers the `:ollama`
adapter, and `:llm/defaults` selects the explicit `provider/model` string for
calls that omit `:llm/model`. Keep the model name concrete and aligned with the
Ollama setup instructions.

After the first successful response, add a clearly secondary “grow this into
application configuration” progression covering, in order: moving the map to a
classpath resource, Aero environment/profile tags, named aliases, multiple
providers, embedding defaults, and optional Integrant composition. Link the
large example config as a reference rather than reproducing it as the first
step.

This issue owns the ordering and content of configuration concepts in the root
quickstart and getting-started material. ONB-009 separately owns how executable
examples load and inject configuration.

## Non-goals and guardrails

- Do not add config constructors such as `ollama-config`, a client object, or a
  second schema layered over the existing map.
- Do not add implicit global configuration, provider/model auto-selection,
  filesystem search, or fallback models. The selected provider and model must
  remain inspectable data.
- Do not remove aliases, Aero, Integrant, multiple providers, or the comprehensive
  example config; move them after first success and preserve their advanced
  documentation.
- Do not duplicate ONB-010's operational readiness/error work or ONB-009's
  example lifecycle changes in this issue.

## Acceptance criteria

- [x] The root quickstart defines the inline two-layer Ollama config before any
  file-backed, alias, Aero, profile, multi-provider, or Integrant material.
- [x] The getting-started guide uses the same minimal map and explanation for
  its first executable generation path.
- [x] The first-success snippets do not require a current working directory,
  environment variable, config constructor, or model alias.
- [x] The shown config validates under the existing `Config` and model
  resolution contracts and reaches the existing Ollama adapter/default URL.
- [x] A focused REPL smoke scenario copies only the documented setup and first
  call and reaches a ready local Ollama model without additional config.
- [x] Advanced material still documents classpath resources, Aero tags,
  aliases, multiple providers, embedding defaults, and Integrant, but only
  after the minimal path.
- [x] No constructor, implicit default provider/model, cwd search, or fallback
  behavior is added to library code.
