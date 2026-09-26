# Document and verify Ollama readiness failures

- **ID:** ONB-010
- **Status:** Resolved
- **Priority:** P0 first-run diagnostics
- **Alpha disposition:** Completed for the next alpha onboarding

## Problem

A valid config proves only that the request can be resolved; it does not prove
that Ollama is listening or that the selected model is installed. Current
onboarding says to install Ollama and pull a model, but gives no readiness check
or concrete interpretation of the failures a user sees when the daemon is
stopped or the model is missing.

The HTTP layer already exposes typed errors rather than hiding operational
failures. The work is to smoke-test the real paths, document them, and improve
wording only if observed output is not actionable. A generic readiness API or
automatic daemon/model mutation would add semantics that the first real request
still cannot guarantee.

## Current evidence

- `src/clj_llm/providers/ollama.clj:134-151` defaults to
  `http://localhost:11434` and sends generation directly to `/api/chat`; there
  is no preceding health or model probe.
- `src/clj_llm/provider.clj` gives adapters default identity/no-op lifecycle
  methods. Provider `start` is lifecycle composition, not readiness.
- `src/clj_llm/http.clj:201-220` throws `:llm/http-error` with `:status`, `:url`,
  and parsed `:body` for non-2xx responses, and `:llm/network-error` with
  `:url` and the original cause for I/O failures.
- `README.md:41-46` says to pull `llama3.2` and start a REPL, but does not show
  how to prove daemon or model readiness before the first generation.
- The Ollama example READMEs say the daemon/model must be ready but do not show
  the stopped, missing-model, and successful outcomes.

## Design work

1. Run and record three focused smoke scenarios against the documented Ollama
   version/model and default localhost URL:
   - daemon stopped, then one `generate` call;
   - daemon ready but a deliberately nonexistent model selected, then one
     `generate` call;
   - daemon ready with the documented model installed, then one successful
     `generate` call.
2. For each failure, capture the user-visible exception message and safe
   `ex-data` shape. Confirm stopped-daemon failure remains
   `:llm/network-error`; confirm model rejection remains `:llm/http-error` with
   its actual status and parsed Ollama body. Do not write assertions around an
   assumed status/body until the real supported Ollama response is observed.
3. Add a short preflight sequence before onboarding code:

   ```sh
   ollama pull llama3.2
   ollama list
   curl -fsS http://localhost:11434/api/version
   ```

   Explain that `ollama list` proves the CLI can reach its daemon and shows
   installed models, while `/api/version` proves the configured default HTTP
   endpoint responds. Mention `ollama serve` only for installations where a
   desktop app or service manager has not already started Ollama.
4. Add a compact diagnostic table mapping observed symptoms to the typed data
   callers should inspect: `(:type (ex-data e))`, `:status`, `:url`, `:body`,
   and the original cause. Distinguish daemon unreachable, model absent, and a
   different provider HTTP rejection.
5. Change library error wording only when the smoke evidence identifies a
   specific ambiguity. Preserve the current type keywords and data keys; add no
   speculative remapping based solely on status code or body text.

This issue owns Ollama operational setup, smoke evidence, and error wording.
ONB-007 owns configuration size; the security endpoint policy remains separate
and must continue allowing explicit local HTTP.

## Implementation evidence — 2026-08-27

The real Ollama smoke used the repository's public `generate` path. The local
daemon was already serving the user's work, so the stopped-daemon contract was
exercised equivalently against the unused port `localhost:11435`; the daemon on
the documented default port was not stopped or disrupted.

The service version was recorded directly:

```sh
curl -fsS http://localhost:11434/api/version
```

```json
{"version":"0.32.5"}
```

The following REPL setup makes the three calls reproducible without credentials
or daemon/model mutation:

```clojure
(require '[clj-llm.core :as llm])

(defn ollama-config [base-url model]
  #:llm{:providers
        {:ollama {:llm/adapter :ollama
                  :base-url base-url}}
        :defaults #:llm{:model (str "ollama/" model)}})

(defn observed-call [config]
  (try
    (select-keys (llm/generate config "Reply with exactly OK.")
                 [:llm/text :llm/model :llm/provider :llm/finish-reason])
    (catch clojure.lang.ExceptionInfo e
      (cond-> {:message (ex-message e)
               :ex-data (select-keys (ex-data e)
                                     [:type :status :url :body])}
        (ex-cause e)
        (assoc :cause (.getName (class (ex-cause e))))))))
```

The unused-port call:

```clojure
(observed-call
 (ollama-config "http://localhost:11435" "llama3.2"))
```

produced these selected safe fields:

```clojure
{:message
 "Network error calling http://localhost:11435/api/chat: ConnectException"
 :ex-data
 {:type :llm/network-error
  :url "http://localhost:11435/api/chat"}
 :cause "java.net.ConnectException"}
```

With the running default daemon, a deliberately absent model:

```clojure
(try
  (llm/generate
   (ollama-config "http://localhost:11434"
                  "clj-llm-definitely-missing")
   "Reply with exactly OK.")
  (catch clojure.lang.ExceptionInfo e
    (select-keys (ex-data e) [:type :status :url :body])))
```

produced HTTP status `404` and these selected `ex-data` fields:

```clojure
{:type :llm/http-error
 :status 404
 :url "http://localhost:11434/api/chat"
 :body {:error "model 'clj-llm-definitely-missing' not found"}}
```

That status/body pair is the response observed from Ollama `0.32.5` for this
request. It is evidence, not a universal Ollama response contract or a library
mapping of every `404` to “model missing”; callers must inspect the actual
`:status` and `:body`.

Finally, the installed onboarding model:

```clojure
(observed-call
 (ollama-config "http://localhost:11434" "llama3.2"))
```

returned:

```clojure
{:llm/text "OK."
 :llm/model "llama3.2"
 :llm/provider :ollama
 :llm/finish-reason :stop}
```

Current test evidence, recorded without rerunning gates for this issue edit:

```sh
bb ci
# 101 tests, 564 assertions; passed

(cd examples/prompt-server && clojure -M:test)
# 5 tests, 13 assertions; passed
```

The documentation surfaces changed by the onboarding and rendering work are
exactly:

- `README.md`
- `examples/README.md`
- `examples/ask/README.md`
- `examples/chat/README.md`
- `examples/prompt-server/README.md`
- `notebooks/getting_started.clj`
- `notebooks/conversations_and_streaming.clj`
- `notebooks/examples.md`
- `notebooks/index.md`

## Final gates and review evidence — 2026-08-27

- Root `bb ci` completed 101 tests with 564 assertions and zero failures or
  errors; formatting and clj-kondo were clean. The focused chat suite completed
  2 tests/5 assertions, and the focused prompt-server suite completed 5 tests/13
  assertions. In particular,
  `network-error-messages-include-actionable-reasons` protects the observed
  network wording for blank and nonblank causes while retaining
  `:llm/network-error`, the safe `:url`, and the original cause.
- The offline book render succeeded with deliberately invalid `OLLAMA_HOST`
  and `OLLAMA_MODEL` values, so the changed documentation rendered without
  relying on a provider request.
- From `/tmp`, `examples/run ask`, `examples/run chat`, and
  `examples/run prompt-server` all exercised their real launch paths: ask and
  the prompt server returned `OK`, while chat loaded its real classpath
  resource and exited normally. The chat and prompt-server focused checks also
  resolved and parsed their real resources.
- The Ollama `0.32.5` smoke outputs above are the exact observed operational
  evidence: the unused-port request reported
  `Network error calling http://localhost:11435/api/chat: ConnectException`
  with `:llm/network-error`, the safe URL, and a
  `java.net.ConnectException` cause; the absent-model request returned
  `:llm/http-error`, status `404`, and
  `{:error "model 'clj-llm-definitely-missing' not found"}`; and installed
  `llama3.2` returned text `"OK."`, model `"llama3.2"`, provider
  `:ollama`, and finish reason `:stop`.
- Both sequential clean adversarial reviews ended **No findings**.

This resolution remains bounded to those observations. The stopped-daemon
contract used the unused localhost port `11435` rather than stopping or
disrupting the running default daemon. The missing-model `404` and parsed body
were observed only from Ollama `0.32.5`; they are not a universal mapping of
`404` responses. No readiness API, health-check guarantee, automatic daemon
control, model mutation, retry, or fallback was introduced.

## Non-goals and guardrails

- Do not add a generic `diagnose`, `ready?`, boolean health check, or provider
  SPI method for this alpha. A successful preflight races the real request, and
  many hosted providers have no free or authoritative readiness endpoint.
- Do not automatically start or stop Ollama, pull/delete models, retry, switch
  models/providers, or mutate user configuration.
- Do not turn provider `start` into a health check or describe it as one.
- Do not collapse typed failures into `false`, catch them silently, or remove
  `:status`, `:url`, `:body`, or the network cause from caller-visible errors.
- Do not infer “model missing” for every 404; document the actual Ollama body
  observed and preserve the general HTTP contract.

## Acceptance criteria

- [x] The stopped-daemon smoke path is recorded and demonstrates a clear
  `:llm/network-error` with safe URL/cause information.
- [x] The ready-daemon/missing-model smoke path is recorded and demonstrates
  the actual `:llm/http-error` status and parsed Ollama error body.
- [x] The ready-daemon/installed-model path returns a normal full `generate`
  response using the exact model named by onboarding.
- [x] Root onboarding and each Ollama example show concrete daemon/model
  preflight commands before the first generation command.
- [x] Documentation explains how to inspect typed exception data and maps each
  observed path to an actionable operator step without exposing secrets.
- [x] Any changed error text is justified by recorded smoke output and retains
  the existing `:llm/network-error`/`:llm/http-error` contracts and keys.
- [x] Focused coverage protects typed failure data if implementation wording
  changes; it does not mock a new readiness API.
- [x] No `diagnose`, boolean readiness, automatic daemon control, model
  mutation, retry, or fallback behavior is introduced.
