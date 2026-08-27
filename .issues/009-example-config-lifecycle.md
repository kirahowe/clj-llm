# Make example configuration lifecycle explicit

- **ID:** ONB-009
- **Status:** Open
- **Priority:** P0 example reliability
- **Alpha disposition:** Must be completed before the next alpha examples are published

## Problem

Each standalone example currently reads `"llm.edn"` in a top-level `def`.
Requiring the namespace therefore performs file I/O immediately and depends on
the process working directory. In the prompt server, the Ring handler also
closes over that hidden global. This makes namespace loading, REPL use, and
handler tests fail before callers reach an explicit application boundary.

`read-config` already accepts classpath resources; the examples need explicit
composition, not filesystem search or delayed global state.

## Current evidence

- `examples/ask/src/example/ask.clj:5-10` evaluates
  `(llm/read-config "llm.edn")` at namespace load and makes `ask` close over
  the resulting var.
- `examples/chat/src/example/chat.clj:5-18` performs the same eager relative
  read before `-main` starts.
- `examples/prompt-server/src/example/prompt_server.clj:6-22` eagerly reads the
  relative file and makes `handler` depend on the global config.
- All three example `deps.edn` files currently have only `"src"` under
  `:paths`; their config files are not classpath resources.
- `src/clj_llm/config.clj:37-42` explicitly accepts anything
  `clojure.java.io/reader` accepts, including `(io/resource ...)`.
- The example READMEs currently compensate by instructing users to run from
  each example directory. That documents the accidental cwd coupling instead
  of removing it.

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

- [ ] Requiring each of the three example namespaces from outside its example
  directory performs no file I/O, config parsing, or provider network call.
- [ ] `ask` uses the inline minimal Ollama config and its callable function
  receives config explicitly.
- [ ] `chat` resolves one fixed classpath resource and reads it at `-main`, with
  a clear startup failure when the resource is absent.
- [ ] `prompt-server` injects config into handler composition at `-main`; its
  handler can be exercised with a supplied config and no filesystem state.
- [ ] Relevant `deps.edn` paths and example config locations match the
  documented classpath-resource lookup.
- [ ] Example READMEs no longer require execution from a specific cwd.
- [ ] Focused namespace-load checks run from an unrelated cwd and focused
  handler checks pass an explicit config.
- [ ] No cwd search, `delay`-backed global, implicit default config, or automatic
  daemon/model mutation is introduced.
