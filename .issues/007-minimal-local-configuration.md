# Lead onboarding with the minimal local configuration

- **ID:** ONB-007
- **Status:** Open
- **Priority:** P0 onboarding
- **Alpha disposition:** Must be completed before the next alpha documentation is published

## Problem

The first-success path currently teaches an Aero file, an Ollama provider, a
model alias, a default alias, and environment fallbacks before the first call.
Those are useful application features, but they make configuration appear more
complex than the public schema requires and introduce file-path failure before
a user can exercise `generate`.

A valid minimal inline Ollama config already exists within the current API. The
onboarding problem does not require a constructor, implicit provider/model
selection, or another configuration language.

## Current evidence

- `src/clj_llm/spec.clj:105-116` requires only `:llm/providers`; both
  `:llm/models` and `:llm/defaults` are optional. `ModelDesignator` accepts a
  `"provider/model"` string at lines 94-97.
- `src/clj_llm/providers/ollama.clj:134-135` already defaults the Ollama base
  URL to `http://localhost:11434`.
- `src/clj_llm/config.clj:31-40` states that inline maps and Aero/Integrant
  results are equivalent to the rest of the library.
- `README.md:18-29` currently leads with `llm.edn`, `#or`, `#env`, a named
  model alias, and a default model alias.
- `resources/clj-llm/config.example.edn` and
  `notebooks/getting_started.clj` present multi-model/Aero features valuable as
  later reference material, not prerequisites for the first response.

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

- [ ] The root quickstart defines the inline two-layer Ollama config before any
  file-backed, alias, Aero, profile, multi-provider, or Integrant material.
- [ ] The getting-started guide uses the same minimal map and explanation for
  its first executable generation path.
- [ ] The first-success snippets do not require a current working directory,
  environment variable, config constructor, or model alias.
- [ ] The shown config validates under the existing `Config` and model
  resolution contracts and reaches the existing Ollama adapter/default URL.
- [ ] A focused REPL smoke scenario copies only the documented setup and first
  call and reaches a ready local Ollama model without additional config.
- [ ] Advanced material still documents classpath resources, Aero tags,
  aliases, multiple providers, embedding defaults, and Integrant, but only
  after the minimal path.
- [ ] No constructor, implicit default provider/model, cwd search, or fallback
  behavior is added to library code.
