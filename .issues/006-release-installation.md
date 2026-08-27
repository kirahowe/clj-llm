# Publish and verify the advertised installation coordinate

- **ID:** ONB-006
- **Status:** Open
- **Priority:** P0 release blocker
- **Alpha disposition:** Must be completed before the next alpha is advertised as a Maven dependency

## Problem

The repository currently tells a new consumer to add
`com.kirahowe/clj-llm {:mvn/version "0.1.0-alpha1"}`, but that coordinate has
not been verified from a clean external project. During the 2026-08-27
onboarding review, the Clojars artifact page and Maven metadata URL for
`com.kirahowe/clj-llm` returned 404; Maven Central metadata also returned 404.
The guidance is therefore false or, at minimum, unverified for a consumer.

The fallback path is incomplete too. `examples/README.md` sends consumers to
`../README.md#installation`, but the root README has no `## Installation`
heading. The checkout examples resolve the library only because their
`deps.edn` files use `{:local/root "../.."}`; that does not prove a released
artifact can be resolved.

## Current evidence

- `README.md:12-16` advertises `com.kirahowe/clj-llm` version
  `0.1.0-alpha1` without naming a verified repository or release check.
- `build.clj:6-10` declares that same library and version and derives the jar
  path from them.
- `build.clj:15-34` writes a POM, copies `src` and `resources`, and creates the
  jar, but no recorded release evidence verifies the resulting jar or POM.
- `build.clj:44-48` calls `deps-deploy/deploy` with only `:artifact` and
  `:pom-file`. The task has not been verified end to end and has no explicit,
  fail-fast deploy-token contract.
- `examples/README.md:13-16` correctly labels `:local/root` as checkout-only,
  then links to the nonexistent root installation anchor.

## Design work

1. Decide whether the next alpha is being published now. Until a Maven
   repository round trip succeeds, replace the unverified Maven snippet with a
   pinned git dependency containing both an immutable release tag and the
   exact commit SHA. Do not use a branch, an unpinned tag, or `:local/root` as
   consumer installation guidance.
2. Run the existing `build.clj/jar` path and inspect both outputs before
   deploying. The jar must contain the intended `src` and `resources` payload
   and Maven metadata, and must exclude tests, examples, notebooks, build
   output, editor/VCS files, and credentials. The POM must contain the exact
   `com.kirahowe/clj-llm` coordinate and version, runtime dependencies, SCM
   tag, project URL, description, and MIT license expected by the build.
3. Verify `build.clj/deploy` end to end and update it to accept a Clojars
   username and deploy token from an explicit secret input such as environment
   variables. Validate both before invoking `deps-deploy`, pass them through
   the supported remote-installer credential fields, and fail with an
   actionable message when either is absent. The token must never be committed,
   written to a project file or generated command, included in exception data,
   or printed by the task or release instructions.
4. Deploy the inspected jar and POM to Clojars. Treat a successful deploy
   command as an intermediate result, not proof of installability.
5. Create a clean project outside this repository, with no local-root or git
   dependency and an empty/fresh dependency cache where practical. Resolve the
   exact documented coordinate from Clojars, require `clj-llm.core`, and run a
   non-network expression that proves the namespace came from the artifact.
6. Only after that clean-project check succeeds, add a real root
   `## Installation` section with the verified Maven coordinate and make every
   consumer link target it. Record the repository, exact version, release tag
   and SHA, and the commands/results used for verification.

## Non-goals and release guardrails

- Do not claim Maven Central availability unless that repository is separately
  configured and verified; publication to Clojars is sufficient for this
  issue.
- Do not add a CI release workflow, versioning framework, signing system, or
  version single-sourcing abstraction unless the verified manual alpha release
  exposes a concrete need.
- Never log the deploy token, place it in `deps.edn`, `build.clj`, shell history,
  generated POM data, or checked-in release documentation, or accept it as a
  normal command-line value visible in process listings.
- Never overwrite an existing immutable Clojars version. Increment the alpha
  version if `0.1.0-alpha1` cannot be published exactly as documented.

## Acceptance criteria

- [ ] The generated jar and POM have been inspected, and release evidence lists
  the expected coordinate/content plus the checked exclusions.
- [ ] `build.clj/deploy` securely accepts and forwards a Clojars deploy token
  and username using the supported `deps-deploy` credential contract.
- [ ] Invoking deploy without either required credential fails before build or
  network work with an actionable message that contains no credential value.
- [ ] A focused deploy-task check proves neither success nor failure output,
  exception data, generated files, nor repository changes contain the token.
- [ ] The inspected jar and POM are deployed to Clojars under one immutable,
  documented alpha coordinate.
- [ ] A clean external project resolves that exact coordinate without
  `:local/root`, git dependencies, or repository checkout state and can require
  `clj-llm.core`.
- [ ] Before publication is verified, all install guidance uses one pinned tag
  plus exact SHA; after verification, Maven guidance replaces it and names the
  exact repository and version.
- [ ] The root README has a real `## Installation` anchor, and the examples
  index link resolves to it.
- [ ] No secret, generated credential file, or release-local configuration is
  committed or printed during the verified release path.
