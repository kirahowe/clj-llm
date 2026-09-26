# Reduce streaming ceremony without changing its contract

- **ID:** ONB-011
- **Status:** Resolved
- **Priority:** P1 onboarding clarity
- **Alpha disposition:** Completed for the next alpha presentation; existing API retained

## Problem

At issue creation, streaming examples repeatedly embedded an anonymous callback
that destructured a chunk, checked its type, printed text, and flushed. The type
check was intentional forward-compatible behavior, but repeating its
implementation inside every `generate` call made the call itself hard to scan
and made streaming appear to be a separate control flow.

The API already had, and still has, the desired semantics: streaming is enabled
with typed `:llm/on-chunk`, and the same synchronous `generate` call returns
the complete canonical response. The presentation fix was to name the callback,
not add another streaming abstraction.

## Evidence at issue creation

- `src/clj_llm/spec.clj:86-89` defines typed chunk maps and requires callbacks
  to ignore event types they do not recognize.
- `src/clj_llm/spec.clj:134-152` keeps `:llm/on-chunk` as an optional function
  on the ordinary request.
- `src/clj_llm/core.clj:264-267` documents that `:text` is the current event
  type, future types may appear, and the full response is still returned.
- `test/clj_llm/core_test.clj:438-449` proves typed chunks reach the callback
  and `generate` returns the complete text response after streaming.
- `README.md:73-82`, `notebooks/conversations_and_streaming.clj`, and
  `examples/chat/src/example/chat.clj:21-25` repeated the anonymous callback.

## Implementation evidence — 2026-08-27

- `README.md`, `notebooks/conversations_and_streaming.clj`, and
  `examples/chat/src/example/chat.clj` now define small named `print-chunk`
  callbacks that handle only `:text`, print and flush at the presentation
  boundary, and are passed through the ordinary opts map.
- The examples retain the complete `generate` response for text, messages,
  and continuation. `test/clj_llm/core_test.clj` covers ordered typed callback
  delivery together with the complete returned response; observed root CI
  completed 101 tests with 564 assertions.
- The observed offline book render succeeded with invalid `OLLAMA_HOST` and
  `OLLAMA_MODEL` values, exercising the revised streaming examples without
  provider access.

## Design work

Define and explain one named callback in each independently copyable example
context, following the same shape:

```clojure
(defn print-chunk
  [{:keys [type text]}]
  (when (= :text type)
    (print text)
    (flush)))
```

Use it as a normal option on the canonical call:

```clojure
(def response
  (llm/generate config "Tell me a short story."
                {:llm/on-chunk print-chunk}))
```

For conversation examples, combine it with history in the same opts map and
retain the returned response for the next turn. Explain that filtering by
`:type` is required so callbacks remain compatible with future events, and
that `flush` belongs to this terminal presentation function rather than to the
library.

Name callbacks for their observable destination (`print-chunk` in terminal
examples), keep them small, and avoid a shared example utility namespace that
would make snippets less self-contained. The examples must demonstrate or
assert that callback delivery does not replace `:llm/text`, `:llm/messages`, or
other final response fields.

This issue owns callback extraction and returned-response explanation only.
ONB-008 owns the prompt/history idiom; ONB-009 owns example config lifecycle.

## Non-goals and guardrails

- Do not add `stream`, `stream!`, `on-text`, raw string callbacks, channels,
  lazy sequences, reducibles, futures, or a second return type.
- Do not remove typed chunks or encourage a callback to print every unknown
  event as text.
- Do not move terminal printing/flushing into library code.
- Do not add cancellation, backpressure, buffering, or asynchronous lifecycle
  semantics without a concrete async requirement and a separate design.
- Do not discard the full returned response after the callback has run; it is
  canonical for continuation and metadata.

## Acceptance criteria

- [x] Root, conversation-guide, and terminal-chat streaming examples pass a
  named callback to `:llm/on-chunk` instead of embedding repeated anonymous
  callback bodies.
- [x] Every named callback accepts a typed chunk, handles only `:text`, and
  safely ignores unknown event types.
- [x] Terminal-specific printing and flushing remain in example callback code,
  not the library.
- [x] Streaming examples bind or otherwise use the complete `generate` return
  value and state that it remains the canonical response.
- [x] Focused behavioral coverage observes ordered typed chunks and the same
  complete final text/messages contract from the returned response.
- [x] Conversation streaming uses the same opts map for `:llm/messages` and
  `:llm/on-chunk`, without manual message construction.
- [x] No new stream function, `on-text` option, channel/lazy return, async
  primitive, or alternate response contract is introduced.

## Final verification evidence — 2026-08-27

- The root `README.md`, rendered conversation book source
  `notebooks/conversations_and_streaming.clj`, and terminal chat example
  `examples/chat/src/example/chat.clj` each use a named `print-chunk` callback.
  Each callback receives typed chunks, handles only `:text`, and keeps terminal
  printing and flushing outside the library.
- Focused complete-response coverage in `test/clj_llm/core_test.clj` observed
  ordered typed callback chunks while the same `generate` call returned the
  complete canonical text and messages response. Root CI completed 101 tests
  and 564 assertions with zero failures or errors; formatting and clj-kondo
  were clean.
- The actual `examples/run chat` streaming smoke loaded and exited successfully,
  exercising the terminal chat path, and the book rendered offline successfully
  with deliberately invalid `OLLAMA_HOST` and `OLLAMA_MODEL` values.
- Two sequential clean adversarial reviews each ended with no findings. The
  reviewed cutover retained typed `:llm/on-chunk` on the ordinary opts map and
  the canonical final response; it introduced no stream function, `on-text`
  option, async primitive, channel/lazy return, or alternate API.
