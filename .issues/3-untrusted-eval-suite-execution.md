# Make executable eval-suite trust explicit in the API

- **Status:** Resolved 2026-08-26
- **Priority:** Medium when suites may come from outside the repository
- **Alpha disposition:** Implemented before the next alpha release

## Problem

File-based eval suites look like EDN data, but qualified `:llm/task` and
scorer symbols were resolved with `requiring-resolve` and invoked in the
current JVM without an explicit capability. Loading the namespace runs its
top-level code; invoking the resolved function grants it the process's
filesystem, environment, network, and credential access. Aero also loads
suite/config sources rather than treating them as inert bytes, so the prior
API assumed suite files and their referenced namespaces were trusted code.

## Resolution evidence

- `src/clj_llm/eval.clj` inspects the validated suite for qualified
  `:llm/task` and scorer symbols before resolving either one. The default
  rejects with `:llm/eval-code-not-allowed` and includes `:symbol`,
  `:role`, `:path`, and `:opt-in {:allow-code? true}`.
- `eval/run` has one executable-code capability, `:allow-code?`, default
  false. Only literal boolean true grants it; false/nil stay safe, and a
  truthy non-boolean fails before suite-source reading with typed
  `:llm/invalid-run-options` data naming the option, value, and accepted
  values. Direct function values in an in-memory suite remain the
  explicitly trusted application path and need no misleading sandbox
  switch.
- The eval CLI preserves `[suite.edn [llm.edn [profile]]]`, adds
  `--allow-code`, `-h`/`--help`, `--` option termination, typed malformed
  invocation errors, and documented exit behavior.
- `test/clj_llm/eval_test.clj` covers inert file suites, default task and
  scorer rejection, exact typed error data, explicit opt-in execution,
  truthy non-boolean rejection without namespace side effects, false/nil
  safe behavior, trusted in-memory task/scorer functions, flag forwarding,
  help/default parsing, and malformed CLI input.
- `test/clj_llm/eval_code_fixture.clj` has an observable top-level system
  property side effect. The file-suite test proves the property remains
  absent after safe-mode rejection; a separate trusted file suite names
  task/scorer vars in the already-loaded test namespace and proves
  explicit opt-in executes both. This remains repeatable in one JVM.
- `notebooks/evals.clj`, the `bb eval` task help, source docstrings, and
  `CHANGELOG.md` document the default, capability, trust boundary, CLI,
  and migration. Generated documentation is intentionally not hand-edited.

## Attack scenario

A user downloads an eval suite from a pull request, benchmark repository, issue attachment, or generated artifact and runs it locally or in CI. The suite names a namespace whose load-time code or resolved task/scorer exfiltrates environment credentials, reads files, or changes the workspace.

The symbol itself must resolve to code on the classpath, so an EDN file alone cannot invent arbitrary bytecode. Dependency additions, repository code, or an already-dangerous classpath function can supply the executable component.

## Resolution

Data-only execution is the default. After loading and validating suite
data, `eval/run` checks every executable designator before any
`requiring-resolve` call. A qualified symbol fails closed with a typed,
actionable exception naming the symbol, whether it was the task or a
scorer, its exact suite path, and the one opt-in.

Trusted callers use `(eval/run config suite {:allow-code? true})`; the CLI
uses `--allow-code`. The API grant requires literal boolean true; false and
nil stay safe, while other values fail with `:llm/invalid-run-options`
before the suite source is read. A valid grant permits both qualified task
and scorer symbols, loads their namespaces, and invokes them in the current
JVM. There is no separate scorer/task capability and no in-process sandbox.

Already-instantiated function values can only arrive through an in-memory
suite constructed by application code, so they remain supported without
`:allow-code?`. Built-in keyword scorers and inert data suites are
unchanged. If isolation is required, run executable suites in a separate
process or container with restricted credentials, filesystem, and network.

## Acceptance criteria

- [x] Public API and CLI documentation state that executable symbols are
  rejected by default and name the exact opt-in.
- [x] Safe mode rejects task/scorer symbols before `requiring-resolve` and
  before loading referenced namespaces.
- [x] Trusted qualified symbols have one explicit capability, while direct
  in-memory task/scorer functions remain supported as trusted application
  code.
- [x] Tests cover built-in data-only suites, both symbol roles, explicit
  opt-in, CLI behavior and malformed invocation, and observable namespace
  load side effects.
