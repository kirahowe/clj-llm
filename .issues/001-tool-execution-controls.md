# Add enforceable controls to automatic tool execution

- **Status:** Resolved 2026-08-26
- **Priority:** High for applications with side-effecting tools
- **Alpha disposition:** Implemented before the next alpha release

## Problem

Provider tool calls are model-controlled, untrusted input. Previously,
when every requested tool matched a supplied `:fn`, `generate` invoked
each function directly with the provider-supplied argument map. The
JSON Schema in `:parameters` was sent to the model but was not runtime
validation or authorization.

`:llm/max-tool-rounds` limited model round trips, not the number of tool
calls. One response could contain many calls, so that bound did not cap
side effects, cost, or work.

## Resolution evidence

- `src/clj_llm/core.clj` preflights each automatic batch before invoking
  any tool function. Unknown tools, policy/validator rejection or
  failure, and insufficient remaining call budget produce local
  `:llm/tool-rejections` and zero executions for that batch.
- The `:llm/max-tool-calls` counter is total across rounds; its
  conservative default is 10, independent of the existing default-10
  `:llm/max-tool-rounds`. A value of 0 disables automatic invocation.
- Policy receives `[normalized-tool-call tool-metadata]`; the runtime
  argument validator receives `[arguments tool-metadata]`. Both use a
  truthy approval contract, and metadata excludes executable `:fn`.
- Provider-bound and replayable requests omit policy/validator
  functions and tool functions. Rejection records are response-local
  and never become conversation messages.
- `test/clj_llm/core_test.clj` covers malicious arguments, policy and
  validator rejection/failure, missing tools, oversized and
  cross-round batches, all-or-none side-effect counters, local
  observability, provider non-disclosure, successful execution,
  sanitized tool-function exceptions, and manual handling.
- `test/clj_llm/spec_test.clj` covers the request controls, zero budget,
  and structured rejection response contract.
- `notebooks/tools.clj` documents the trust boundary, hook contracts,
  default budget, rejection records, all-or-none behavior, and manual
  path. `CHANGELOG.md` records the new API and migration from the
  previously unbounded default.

## Attack scenario

An application exposes untrusted user prompts and registers a tool that
sends email, mutates data, reads files, spends money, or calls another
service. Prompt injection or a compromised provider produces a
syntactically valid call with unauthorized arguments—or a large batch
of calls. Without preflight controls, registration alone was sufficient
for invocation.

The tool implementation and its application remain responsible for
domain authorization. The resolved API supplies a common enforcement
point and budget without treating provider schema conformance as trust.

## Resolution

Automatic execution now has one explicit enforcement point and no
partial-batch mode:

1. Match every provider call to a configured tool. An unknown name
   rejects the batch; a matched tool without `:fn` preserves the
   existing manual path for the complete batch.
2. Ensure the remaining `:llm/max-tool-calls` budget can cover the
   complete batch.
3. Run `:llm/tool-policy`, then
   `:llm/tool-argument-validator`, for each automatic call. A false/nil
   return or exception rejects the batch.
4. Invoke functions only after every call passes preflight.

The library intentionally does not interpret the JSON Schema under
`:parameters`; applications can supply a complete validator appropriate
to their schema dialect and security requirements.

Rejection reason keywords are `:tool-not-found`,
`:tool-policy-rejected`, `:tool-policy-error`,
`:tool-arguments-rejected`, `:tool-argument-validator-error`, and
`:tool-call-budget-exceeded`. Each record includes the normalized
`:tool-call`; hook failures add a local `:message`, and budget failures
add `:budget {:limit :used :requested :remaining}`.

## Reviewer notes

- Policy and validator callbacks are part of the trusted application
  boundary and should be deterministic and side-effect free: preflight
  may run them for calls in a batch that is ultimately rejected.
- Hook exception messages are intentionally caller-local for diagnosis
  but may contain sensitive data; applications should apply their normal
  response logging controls.
- Reaching `:llm/max-tool-rounds` retains the existing pending/manual
  result rather than creating a tool rejection, because no automatic
  invocation is attempted at that point.
- The default call cap can reject workloads that previously executed
  more than 10 calls; migration is to set an explicit, reviewed total.

## Acceptance criteria

- [x] A configured policy can prevent a matching `:fn` from being
  invoked.
- [x] A total call budget counts automatic invocations across every
  round and rejects a batch that cannot fit the remaining budget.
- [x] Invalid or rejected calls are observable locally without leaking
  rejection data or internal hook details to the provider.
- [x] Existing manual execution remains available and documented.
- [x] Focused tests cover malicious arguments, policy/validator
  rejection and failure, missing tools, oversized and multiple-round
  batches, all-or-none side effects, successful execution, actual tool
  exceptions, provider non-disclosure, and manual handling.
