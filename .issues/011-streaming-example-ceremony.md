# Reduce streaming ceremony without changing its contract

- **ID:** ONB-011
- **Status:** Open
- **Priority:** P1 onboarding clarity
- **Alpha disposition:** Complete the presentation cleanup before the next alpha; retain the existing API

## Problem

Streaming examples repeatedly embed an anonymous callback that destructures a
chunk, checks its type, prints text, and flushes. The type check is intentional
forward-compatible behavior, but repeating its implementation inside every
`generate` call makes the call itself hard to scan and makes streaming appear
to be a separate control flow.

The current API already has the desired semantics: streaming is enabled with
typed `:llm/on-chunk`, and the same synchronous `generate` call still returns
the complete canonical response. The fix is to name the presentation callback,
not add another streaming abstraction.

## Current evidence

- `src/clj_llm/spec.clj:86-89` defines typed chunk maps and requires callbacks
  to ignore event types they do not recognize.
- `src/clj_llm/spec.clj:134-152` keeps `:llm/on-chunk` as an optional function
  on the ordinary request.
- `src/clj_llm/core.clj:264-267` documents that `:text` is the current event
  type, future types may appear, and the full response is still returned.
- `test/clj_llm/core_test.clj:438-449` proves typed chunks reach the callback
  and `generate` returns the complete text response after streaming.
- `README.md:73-82`, `notebooks/conversations_and_streaming.clj`, and
  `examples/chat/src/example/chat.clj:21-25` repeat the anonymous callback.

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

- [ ] Root, conversation-guide, and terminal-chat streaming examples pass a
  named callback to `:llm/on-chunk` instead of embedding repeated anonymous
  callback bodies.
- [ ] Every named callback accepts a typed chunk, handles only `:text`, and
  safely ignores unknown event types.
- [ ] Terminal-specific printing and flushing remain in example callback code,
  not the library.
- [ ] Streaming examples bind or otherwise use the complete `generate` return
  value and state that it remains the canonical response.
- [ ] Focused behavioral coverage observes ordered typed chunks and the same
  complete final text/messages contract from the returned response.
- [ ] Conversation streaming uses the same opts map for `:llm/messages` and
  `:llm/on-chunk`, without manual message construction.
- [ ] No new stream function, `on-text` option, channel/lazy return, async
  primitive, or alternate response contract is introduced.
