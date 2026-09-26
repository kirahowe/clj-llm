# Make example configuration lifecycle explicit

- **ID:** ONB-009
- **Status:** Resolved
- **Priority:** P0 example reliability
- **Alpha disposition:** Completed for the next alpha examples

## Problem

At issue creation, each standalone example read `"llm.edn"` in a top-level
`def`. Requiring the namespace therefore performed file I/O immediately and
depended on the process working directory. In the prompt server, the Ring
handler also closed over that hidden global. This made namespace loading, REPL
use, and handler tests fail before callers reached an explicit application
boundary.

`read-config` already accepted classpath resources; the examples needed
explicit composition, not filesystem search or delayed global state.

## Evidence at issue creation

- `examples/ask/src/example/ask.clj:5-10` evaluated
  `(llm/read-config "llm.edn")` at namespace load and made `ask` close over
  the resulting var.
- `examples/chat/src/example/chat.clj:5-18` performed the same eager relative
  read before `-main` started.
- `examples/prompt-server/src/example/prompt_server.clj:6-22` eagerly read the
  relative file and made `handler` depend on the global config.
- All three example `deps.edn` files had only `"src"` under `:paths`; their
  config files were not classpath resources.
- `src/clj_llm/config.clj:37-42` explicitly accepts anything
  `clojure.java.io/reader` accepts, including `(io/resource ...)`.
- The example READMEs compensated by instructing users to run from each example
  directory. That documented the accidental cwd coupling instead of removing
  it.

## Implementation evidence — 2026-08-27

- `examples/ask/src/example/ask.clj` now passes its minimal inline config
  explicitly. `examples/chat/deps.edn` and
  `examples/prompt-server/deps.edn` add `resources`; the config files are
  `examples/chat/resources/example/chat/llm.edn` and
  `examples/prompt-server/resources/example/prompt-server/llm.edn`.
  `examples/chat/src/example/chat.clj` and
  `examples/prompt-server/src/example/prompt_server.clj` resolve those fixed
  resources in `-main` before lexical loop/handler composition.
- `examples/run`, `examples/README.md`, and each example's `README.md`
  support launching ask, chat, or prompt-server from any caller cwd. The runner
  changes directory only to select the example project's Clojure classpath;
  config uses inline data or a fixed classpath resource, never a cwd search.
  Observed runs of all three commands succeeded from `/tmp`.
- `examples/chat/test/example/chat_test.clj` resolves and parses the real chat
  resource and confirms `-main` loads it before quitting without generation.
  `examples/prompt-server/test/example/prompt_server_test.clj` resolves and
  parses its real resource, injects config into handler checks, and observes
  startup composition. Results were 2 tests/5 assertions for chat and 5
  tests/13 assertions for prompt-server.

## Final verification — 2026-08-27

- Focused pure namespace-load checks for `example.ask`, `example.chat`, and
  `example.prompt-server` completed from an unrelated working directory
  without loading config or contacting a provider. The ask entry point then
  received its inline minimal config explicitly.
- The real fixed chat and prompt-server classpath resources resolved and parsed.
  The chat checks passed 2 tests/5 assertions, including loading the resource at
  `-main` and exiting before generation; the prompt-server checks passed 5
  tests/13 assertions, including explicit config injection into the handler and
  startup composition.
- From `/tmp`, `examples/run ask`, `examples/run chat`, and
  `examples/run prompt-server` all exercised their documented launch paths:
  ask and prompt-server returned `OK`, while chat loaded and exited normally.
  These runs used inline config or the parsed fixed resources rather than a
  caller-cwd config file.
- Two sequential adversarial reviews completed with no findings after the
  lifecycle, resource paths, handler injection, and unrelated-cwd behavior were
  verified.

## Design work

Apply a distinct lifecycle appropriate to each example:

1. **ask:** keep the first example self-contained with the minimal inline
   Ollama map from ONB-007. Change `ask` to accept config explicitly, and have
   `-main` compose that config with the prompt. Requiring `example.ask` must not
   read a file or contact a provider.
2. **chat:** place its EDN config on an explicit classpath resource path, add
   the resource directory to `deps.edn`, resolve it with
   `clojure.java.io/resource`, fail clearly if it is absent, and call
   `llm/read-config` once inside `-main`. Pass the resulting value through the
   chat loop's lexical scope. Requiring `example.chat` must perform no config
   I/O.
3. **prompt-server:** make handler composition accept a config and return or
   partially apply a Ring handler that closes over that explicit value. Resolve
   a classpath config resource and compose the handler inside `-main`, before
   passing it to Jetty. Direct handler tests must supply config without loading
   repository files, and requiring the namespace must perform no config I/O.
4. Update each example README to describe its actual lifecycle and remove
   instructions that require a particular current working directory.

Use a small helper only where it makes missing-resource handling clearer. The
resource lookup must identify one fixed classpath name and fail at startup with
an actionable, non-secret message when missing.

This issue owns example config source, load timing, and injection only. ONB-007
owns quickstart configuration pedagogy, ONB-008 owns the `generate` prompt/history
shape, and ONB-011 owns the named streaming callback.

## Non-goals and guardrails

- Do not search the current directory, parent directories, home directory, or
  environment-specific fallback paths.
- Do not use `delay` as the primary fix. It preserves hidden global state,
  memoizes failures in a REPL, and merely postpones cwd dependence.
- Do not add implicit library config loading, global mutable config, Integrant
  as an example prerequisite, or automatic provider startup.
- Do not weaken the prompt server's existing loopback binding, request-size
  limit, or testability while changing handler composition.

## Acceptance criteria

- [x] Requiring each of the three example namespaces from outside its example
  directory performs no file I/O, config parsing, or provider network call.
- [x] `ask` uses the inline minimal Ollama config and its callable function
  receives config explicitly.
- [x] `chat` resolves one fixed classpath resource and reads it at `-main`, with
  a clear startup failure when the resource is absent.
- [x] `prompt-server` injects config into handler composition at `-main`; its
  handler can be exercised with a supplied config and no filesystem state.
- [x] Relevant `deps.edn` paths and example config locations match the
  documented classpath-resource lookup.
- [x] Example READMEs no longer require execution from a specific cwd.
- [x] Focused namespace-load checks run from an unrelated cwd and focused
  handler checks pass an explicit config.
- [x] No cwd search, `delay`-backed global, implicit default config, or automatic
  daemon/model mutation is introduced.
