---
name: verify
description: Pre-release verification runbook for clj-llm. Exercises every documented user path — README quickstart, error diagnostics, all three examples, the eval CLI, the OpenAI-compatible adapter, the jar/install/consume round trip, and the docs build — against live Ollama. Use before cutting a release or after changes to the public API, README, or examples.
---

# Verify clj-llm from a user's perspective

Run every documented user path end-to-end and confirm it behaves as the docs
promise. Steps are ordered cheapest-first; run them sequentially — parallel
JVMs plus Ollama inference load the machine enough to flake the timing test
(see Gotchas). All commands run from the repository root.

Every `clojure` / `bb` command needs the JDK on PATH first:

```sh
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
```

## 0. Prerequisites

```sh
curl -fsS http://localhost:11434/api/version   # Ollama server up
ollama list                                    # must include llama3.2 and nomic-embed-text
```

Pull anything missing: `ollama pull llama3.2`, `ollama pull nomic-embed-text`.

## 1. Static suite

```sh
bb ci        # fmt + lint + test:integrant; expect 0 failures
```

If exactly `non-streaming-timeout-covers-body-consumption` fails, rerun on a
quiet machine before treating it as a regression (see Gotchas).

## 2. README quickstart and common tasks

```sh
clojure -M .claude/skills/verify/scripts/quickstart.clj
```

Covers generate, conversation continuation, streaming, structured output,
tools, embeddings, and evals — the exact README snippets. Exit 0 and a final
`QUICKSTART PASSED` line mean every step checked out. **The scripts mirror
README snippets; when the README changes, update them in the same commit.**

## 3. Documented error diagnostics

```sh
clojure -M .claude/skills/verify/scripts/diagnostics.clj
```

Confirms the README diagnostics table: `:llm/network-error` (unreachable
port, cause preserved), `:llm/http-error` (missing model, status + decoded
body), and `:llm/config-error` for an unknown provider prefix.

## 4. OpenAI-compatible adapter, live

```sh
clojure -M .claude/skills/verify/scripts/openai_compat.clj
```

Drives the `:openai` adapter against Ollama's `/v1` endpoint — no API key
needed; this is the OpenRouter/Groq/vLLM/LM Studio user path.

## 5. Examples

```sh
./examples/run ask "Why is the sky blue? Answer in one sentence."
echo "Explain immutable data in one sentence" | ./examples/run ask
printf 'What is 2+2?\nNow multiply that by 10.\n:quit\n' | ./examples/run chat
```

The chat's second answer must use the first turn's result (proves history is
carried). Then the server (background it, curl it, kill it):

```sh
./examples/run prompt-server &   # wait ~10s for Jetty
curl -sS --max-time 60 --data 'Give me one sentence about immutable data.' \
  http://127.0.0.1:3000/generate
kill %1
```

Example test suites (isolated, no Ollama):

```sh
(cd examples/chat && clojure -M:test)
(cd examples/prompt-server && clojure -M:test)
```

## 6. Eval CLI

```sh
bb eval .claude/skills/verify/scripts/eval-suite.edn \
        .claude/skills/verify/scripts/eval-llm.edn
```

Expect a summary table with 0 errors and exit 0.

## 7. Packaging round trip (consumer simulation)

The coordinate and version come from `build.clj` (`lib`, `version`).

```sh
bb jar && bb install
mkdir -p "$SCRATCH/consumer" && cd "$SCRATCH/consumer"
printf '{:deps {com.kirahowe/clj-llm {:mvn/version "%s"}}}\n' "<version>" > deps.edn
clojure -M -e "(require '[clj-llm.core :as llm])
               (println (:llm/text (llm/generate
                 #:llm{:providers {:ollama {:llm/adapter :ollama}}
                       :defaults #:llm{:model \"ollama/llama3.2\"}}
                 \"Say hello in three words.\")))"
```

Use a scratch directory *outside* the checkout so the classpath can only come
from the installed jar. A non-empty answer means a post-release consumer can
resolve, require, and call the library.

## 8. Docs build

```sh
bb book      # requires quarto on PATH (/usr/local/bin)
```

Expect all notebooks rendered and output in the gitignored `docs/`. Check the
worktree afterward — the build must not modify tracked files.

## 9. When provider API keys are present (optional)

`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` allow one tiny live call through the
`:anthropic` / `:openai` adapters against the real services. Without keys
those adapters are covered by the unit suite and step 4 only — note the gap
in the report.

## Gotchas learned running this

- **JDK is not on PATH in non-interactive shells** — every step needs the
  `export PATH` line above or `clojure` dies with "no java".
- **Capture full output to a file** when piping forms into a bare `clojure`
  REPL: prompts and printed output share lines, so `grep`/`tail` filters
  silently eat markers. The scripts here avoid this by running via
  `clojure -M script.clj` with explicit exit codes.
- **`non-streaming-timeout-covers-body-consumption` is load-sensitive**: a
  30 ms timeout raced against a 150 ms slow body flakes when Ollama inference
  or parallel JVMs saturate the machine (cause surfaces as `IOException`
  instead of `HttpTimeoutException`). Isolated reruns pass.
- **Provider-map adapter keys are unqualified** (`:base-url`, `:api-key`).
  Writing `:llm/base-url` is *silently ignored* and the adapter uses its
  default URL — a false-positive trap for any test that expects a request to
  fail against a bad URL.
- **zsh eats unquoted `=`-prefixed words** (`echo ===` fails); quote
  separators in shell one-liners.
- **This repo is jj (colocated)** — check `jj st` before committing;
  other sessions' in-flight edits may be in the working copy, so commit by
  explicit paths.
