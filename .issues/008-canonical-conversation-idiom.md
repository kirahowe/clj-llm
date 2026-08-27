# Standardize the canonical conversation idiom

- **ID:** ONB-008
- **Status:** Open
- **Priority:** P0 onboarding consistency
- **Alpha disposition:** Must be completed before the next alpha documentation and examples are published

## Problem

The repository teaches multiple equivalent ways to continue a conversation at
the moment a new user needs one clear progression. Some snippets manually
construct and append `{:role :user :content prompt}`; others put both
`:llm/messages` and `:llm/prompt` in a request map. The smallest public idiom is
already supported: keep the prompt as the positional argument and put prior
history in the options map.

This is a presentation inconsistency, not a missing chat abstraction. The
library is intentionally stateless and the response's `:llm/messages` remains
the conversation value passed to the next call.

## Current evidence

- `src/clj_llm/core.clj:237-249` documents `generate` as stateless and accepts a
  prompt string or request map; `:llm/prompt` is folded after existing
  `:llm/messages`.
- `src/clj_llm/core.clj:84-96` implements that ordering by converting history
  to a vector and appending the positional prompt as the trailing user message.
- `test/clj_llm/core_test.clj:154-168` proves that a prompt string plus
  `{:llm/messages history}` produces the same ordered request as the request-map
  form.
- `README.md:62-71` currently teaches the request-map form for continuation.
- `notebooks/conversations_and_streaming.clj:15-30` presents manual user-message
  construction and the prompt form as alternatives.
- `examples/chat/src/example/chat.clj:17-25` manually conjoins a user message
  instead of using the positional prompt.

## Design work

Use one call shape for the first turn and continuation across the root README,
core-facing guides, notebooks, and standalone examples:

```clojure
(def first-response
  (llm/generate config "Name a prime number."))

(def next-response
  (llm/generate config "Why is it prime?"
                {:llm/messages (:llm/messages first-response)}))
```

Teach `(generate config prompt)` for a first call. Introduce
`(generate config prompt opts)` only when the caller has history or another
setting to pass: the prompt stays positional and options belong in the third
argument. Continue from the full returned `:llm/messages`, not from `:llm/text`
and not from a separately maintained chat object.

Move manual construction of role/content message maps to one advanced section
for callers that need imported history, non-user roles, manual tool turns, or
request-map composition. State there that the public prompt-or-request-map
behavior remains supported; this issue changes the recommended default, not the
accepted API.

This issue owns only the prompt/history call shape. ONB-009 owns config loading
and dependency injection in examples; ONB-011 owns streaming callback
presentation.

## Non-goals and guardrails

- Do not remove or narrow public request-map input, `:llm/prompt`, or explicit
  `:llm/messages` behavior.
- Do not add a `Chat`, `Conversation`, or client object, mutable session state,
  or a conversation helper that merely wraps `generate`.
- Do not make examples reconstruct history from provider wire responses or
  append an extra user message manually before passing a positional prompt.
- Do not combine this issue with callback naming or config lifecycle changes;
  those have separate acceptance criteria.

## Acceptance criteria

- [ ] The root README, getting-started/conversation material, and all ordinary
  examples use a positional prompt with an opts map for request options.
- [ ] Every continuation example passes the prior response's complete
  `:llm/messages` under opts and relies on `generate` to append the new prompt.
- [ ] First-turn and continuation snippets use the same data flow and do not
  manually conjoin the next user message.
- [ ] Manual message/request-map construction appears only in clearly labeled
  advanced material with a concrete need for role-level control.
- [ ] Public request-map calls remain supported and their existing behavioral
  coverage is retained.
- [ ] Focused conversation coverage proves the positional prompt is appended
  after prior history exactly once and the returned messages can feed the next
  turn.
- [ ] No chat/client/session abstraction or mutable conversation state is
  introduced.
