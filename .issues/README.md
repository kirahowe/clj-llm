# Repository issue index

This directory records repository-local engineering issues with stable IDs.
Update status and evidence in the individual file when work lands; keep
filenames and IDs stable so commits and future discussions can link to them.
These are implementation records, not published vulnerability advisories.

## Open onboarding and release work

These issues turn the reviewed earliest-user feedback into prescriptive work
for the next alpha. Their ownership is intentionally separated: release and
installation, configuration pedagogy, conversation calls, example config
lifecycle, Ollama operations, and streaming presentation.

| ID | Issue | Priority | Current disposition |
| --- | --- | --- | --- |
| ONB-006 | [Publish and verify the advertised installation coordinate](006-release-installation.md) | P0 release blocker | Open; required before advertising the next alpha as a Maven dependency |
| ONB-007 | [Lead onboarding with the minimal local configuration](007-minimal-local-configuration.md) | P0 onboarding | Open; required before next-alpha documentation |
| ONB-008 | [Standardize the canonical conversation idiom](008-canonical-conversation-idiom.md) | P0 onboarding consistency | Open; required before next-alpha documentation and examples |
| ONB-009 | [Make example configuration lifecycle explicit](009-example-config-lifecycle.md) | P0 example reliability | Open; required before next-alpha examples |
| ONB-010 | [Document and verify Ollama readiness failures](010-ollama-readiness-errors.md) | P0 first-run diagnostics | Open; required before next-alpha onboarding |
| ONB-011 | [Reduce streaming ceremony without changing its contract](011-streaming-example-ceremony.md) | P1 onboarding clarity | Open; presentation cleanup before next alpha |

## Security review issues

The security findings and boundary decisions below come from the pre-alpha
review begun on 2026-08-25. Start here before changing tool execution, HTTP
transport, eval loading, provider configuration, or the prompt-server example.

| ID | Issue | Priority | Current disposition |
| --- | --- | --- | --- |
| SEC-001 | [Add enforceable controls to automatic tool execution](001-tool-execution-controls.md) | High for side-effecting tools | Resolved; automatic batches have policy, validation, and total-call-budget preflight |
| SEC-002 | [Bound provider response bodies and streams](002-provider-response-limits.md) | Medium, higher for untrusted endpoints | Resolved; shared size, line, cumulative, whole-stream, and idle limits cover built-in adapters |
| SEC-003 | [Make executable eval-suite trust explicit in the API](003-untrusted-eval-suite-execution.md) | Medium for externally sourced suites | Resolved; qualified executable symbols require explicit API/CLI opt-in |
| SEC-004 | [Define a policy for custom provider endpoints](004-provider-endpoint-policy.md) | Medium when config is untrusted | Resolved; built-in destinations are validated and support a normalized allowlist hook |
| SEC-005 | [Keep the prompt-server example out of production](005-prompt-server-production-boundary.md) | Low while loopback-only | Accepted limitation with regression coverage; reopen if example scope expands |

No implementation issue remains open from the security review. SEC-005 remains
a deliberate security guardrail rather than a production-service design. The
open onboarding/release issues above do not reopen those resolved findings.

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
