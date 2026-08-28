# Standardize the canonical conversation idiom

- **ID:** ONB-008
- **Status:** Resolved
- **Priority:** P0 onboarding consistency
- **Alpha disposition:** Completed for the next alpha documentation and examples

## Problem

At issue creation, the repository taught multiple equivalent ways to continue
a conversation at the moment a new user needed one clear progression. Some
snippets manually constructed and appended `{:role :user :content prompt}`;
others put both `:llm/messages` and `:llm/prompt` in a request map. The
smallest public idiom was already supported: keep the prompt as the positional
argument and put prior history in the options map.

This was a presentation inconsistency, not a missing chat abstraction. The
library is intentionally stateless, and the response's `:llm/messages` remains
the conversation value passed to the next call.

## Evidence at issue creation

- `src/clj_llm/core.clj:237-249` documents `generate` as stateless and accepts a
  prompt string or request map; `:llm/prompt` is folded after existing
  `:llm/messages`.
- `src/clj_llm/core.clj:84-96` implements that ordering by converting history
  to a vector and appending the positional prompt as the trailing user message.
- `test/clj_llm/core_test.clj:154-168` proves that a prompt string plus
  `{:llm/messages history}` produces the same ordered request as the request-map
  form.
- `README.md:62-71` taught the request-map form for continuation.
- `notebooks/conversations_and_streaming.clj:15-30` presented manual
  user-message construction and the prompt form as alternatives.
- `examples/chat/src/example/chat.clj:17-25` manually conjoined a user message
  instead of using the positional prompt.

## Implementation evidence — 2026-08-27

- `README.md`, `notebooks/conversations_and_streaming.clj`, and
  `examples/chat/src/example/chat.clj` now use a positional prompt with prior
  `:llm/messages` in the opts map; continuation starts from the complete
  response rather than manually appending a user message.
- `notebooks/getting_started.clj` identifies positional prompts plus options as
  the ordinary API and confines request-map construction to its advanced
  imported-transcript example.
- `test/clj_llm/core_test.clj` covers prompt-after-history ordering and reuse
  of returned messages. Observed root CI completed 101 tests with 564
  assertions.
- The observed offline book render succeeded with invalid `OLLAMA_HOST` and
  `OLLAMA_MODEL` values, exercising the revised conversation material without
  provider access.

## Final verification evidence — 2026-08-27

- Root `bb ci` passed 101 tests and 564 assertions with zero failures or
  errors. This includes the focused continuation case "positional prompts
  append once and returned messages continue the history" and the focused
  `streaming-callback-passthrough` coverage in `test/clj_llm/core_test.clj`.
- The revised README and conversation material rendered successfully in the
  offline book with deliberately invalid `OLLAMA_HOST` and `OLLAMA_MODEL`
  values, confirming that documentation rendering does not require a provider.
- The standalone chat example resolved its real resources, loaded, and exited
  successfully when launched through `examples/run chat` from `/tmp`.
- Request-map input remains supported for callers needing the advanced API;
  its existing root behavioral coverage passed while ordinary examples use
  positional prompts and immutable returned `:llm/messages` values. No mutable
  chat, client, session, or separately maintained conversation object was
  introduced.
- Two sequential adversarial reviews completed with no findings.

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

- [x] The root README, getting-started/conversation material, and all ordinary
  examples use a positional prompt with an opts map for request options.
- [x] Every continuation example passes the prior response's complete
  `:llm/messages` under opts and relies on `generate` to append the new prompt.
- [x] First-turn and continuation snippets use the same data flow and do not
  manually conjoin the next user message.
- [x] Manual message/request-map construction appears only in clearly labeled
  advanced material with a concrete need for role-level control.
- [x] Public request-map calls remain supported and their existing behavioral
  coverage is retained.
- [x] Focused conversation coverage proves the positional prompt is appended
  after prior history exactly once and the returned messages can feed the next
  turn.
- [x] No chat/client/session abstraction or mutable conversation state is
  introduced.
