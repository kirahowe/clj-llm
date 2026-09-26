# Review notes

Context carried over from the handwritten index that preceded the `issues`
CLI. Issue SEC-00N is now issue N and ONB-0NN is issue NN; each keeps its
old ID as `:legacy-id`.

## Earlier findings resolved during the review

These findings were fixed and committed; they are listed here to prevent duplicate investigation:

- **Tool exception disclosure:** provider-visible tool errors no longer include `ex-message`. Regression coverage uses a distinctive secret and proves it does not enter the next provider request or final response. Commit `6bd0dcc`.
- **Credential forwarding through redirects:** the shared `HttpClient` uses `Redirect/NEVER`; integration coverage proves a `307` destination is not requested. Commit `6bd0dcc`.
- **Affected Jackson artifact versions:** Jackson Core, Smile, and CBOR are pinned to 2.21.4 rather than Cheshire 6.2.0's 2.21.1 transitive versions. The reviewed code uses synchronous parsing, but the fixed versions remove the affected asynchronous parser artifacts. Commit `6bd0dcc`.
- **Prompt-server network/body exposure:** the example binds to `127.0.0.1` and rejects prompt bodies over 64 KiB. Commit `5b711aa`.
- **Missing trust-boundary guidance:** README, tool docs, eval docs, example docs, and changelog now identify model calls, executable suites, and the demonstration server's security boundaries. Commit `5b711aa`.

## Review scope and no-findings context

The review traced runtime entry points, provider HTTP requests and responses, tool execution, config and secret flow, eval symbol loading, parsing/serialization, examples, and direct dependencies. It did not find shell/process execution, Java native deserialization, or provider API keys being copied into normal interaction request records. Absence of a finding is not a guarantee; re-review these assumptions when adding adapters, persistence, remote configuration, or tool runtimes.

## Future-self checklist

When closing an issue:

1. Update the issue's status, final decision, and exact verification evidence.
2. Add focused behavioral tests when observable behavior or a trust boundary
   changes; test the contract rather than schema or plumbing alone.
3. For documentation and examples, run a copy-paste check or exercise the
   actual user-facing surface described by the changed material.
4. Update README/notebook sources and regenerate published docs when
   user-facing behavior changes.
5. Record migrations in `CHANGELOG.md` if defaults or public request keys
   change.
6. Run the applicable focused checks and CI, plus a real smoke scenario for the
   changed surface; do not require unrelated trust-boundary checks.
7. Mark the issue resolved only when its implementation, evidence, and
   applicable release documentation land together.
