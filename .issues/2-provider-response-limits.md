# Bound provider response bodies and streams

- **Status:** Resolved 2026-08-26
- **Priority:** Medium; high when provider endpoints are not fully trusted
- **Alpha disposition:** Resolved in the shared transport for every built-in adapter

## Problem

The HTTP transport had connection and request timeouts but no response-size
bound. Non-streaming responses were buffered completely. Streaming responses
could send an unlimited number of bytes or keep a reader occupied indefinitely
after response headers arrived.

Provider stream reducers also accumulated generated text and tool-call
argument fragments in memory. A malicious, compromised, or badly behaving
endpoint could exhaust heap or retain a thread with a slow or infinite stream.

## Evidence

- `src/clj_llm/http.clj` uses `BodyHandlers/ofInputStream` and a bounded
  max-plus-one reader for non-streaming success and error bodies, so rejected
  bodies are never accumulated before the limit decision.
- The shared streaming reducer bounds UTF-8 line bytes and cumulative decoded
  body bytes, including line delimiters, without `readLine` buffering.
- A shared daemon `ScheduledThreadPoolExecutor` closes blocked streaming reads
  at whole-stream or idle-read deadlines and preserves the existing
  non-streaming whole-request deadline during bounded body reads. Cancelled
  watchdogs are removed, and every success, reduction, exception, and limit
  path closes the input stream.
- Limit failures are `ex-info` with `:type :llm/response-limit`,
  `:limit-kind`, and `:limit`. They never include `:body`; HTTP status is
  retained when a bounded non-streaming body is rejected.
- Anthropic, OpenAI-compatible, and Ollama adapters all forward the same
  provider transport options through `clj-llm.http/request-options`.
- Every HTTP numeric option is validated as a positive integer before request
  construction; invalid values throw `:llm/config-error` with `:key` and
  `:value`.
- `test/clj_llm/http_test.clj` exercises exact and over-limit multibyte bodies,
  64 KiB bodies and unterminated lines, error bodies, cumulative bytes, normal
  SSE and NDJSON, idle, stream, and non-streaming deadlines, invalid options,
  every built-in generation and embedding forwarding path, reducer latency
  semantics, and server-observed stream closure.

## Attack scenario

A configured provider endpoint responds with a very large JSON document, one enormous SSE/NDJSON line, an unlimited sequence of small lines, or a stream that sends occasional bytes forever. The request passes connection/header deadlines but consumes memory or a worker thread until the process becomes unavailable.

## Resolution

The following unqualified provider keys are also accepted directly by the
public HTTP request functions:

| Key | Default | Meaning |
|---|---:|---|
| `:max-response-bytes` | 8 MiB | decoded success or error body |
| `:max-stream-line-bytes` | 1 MiB | one decoded SSE/NDJSON line, excluding delimiter |
| `:max-stream-bytes` | 32 MiB | cumulative decoded stream bytes, including delimiters |
| `:stream-timeout-ms` | 600000 | whole stream after response headers |
| `:stream-idle-timeout-ms` | 60000 | one blocked provider read; reducer time is excluded |

Existing `:timeout-ms` remains 120 seconds. It covers the full response for
non-streaming calls and reaches through response headers for streaming calls.
Limit failures use `:limit-kind` values `:response-bytes`,
`:stream-line-bytes`, `:stream-bytes`, `:stream-timeout-ms`, or
`:stream-idle-timeout-ms`.

Compression is not requested or decoded by the transport. The byte limits
therefore cover the decoded UTF-8 representation currently retained by the
application; any future compression support must apply these checks after
decompression.

## Acceptance criteria

- [x] Non-streaming success and error bodies stop at the configured byte limit without first buffering the entire body.
- [x] Streaming stops on oversized lines, cumulative byte limits, whole-stream deadline, and idle deadline.
- [x] Input streams and response resources close on every limit/error path.
- [x] All built-in adapters receive consistent typed errors.
- [x] In-process HTTP tests exercise a large body, large line, endless/slow stream, boundary-sized response, and normal SSE/NDJSON behavior.
