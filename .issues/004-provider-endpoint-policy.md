# Define a policy for custom provider endpoints

- **Status:** Resolved 2026-08-26
- **Priority:** Medium only when applications accept untrusted provider configuration
- **Alpha disposition:** Implemented before the next alpha release

## Problem

Provider `:base-url` and custom headers are intentionally configurable to
support OpenAI-compatible services and local models. Built-in adapters send
prompts and, where configured, API credentials to those endpoints. Previously,
destinations had no scheme, host, or port preflight beyond what
`java.net.http` accepted, and there was no application policy hook.

This was not an SSRF vulnerability when configuration was local and trusted.
It became one if an application derived provider configuration from tenants,
HTTP requests, uploaded files, or other untrusted input. A hostile endpoint
could receive prompts and credentials and target loopback, link-local, or
private services reachable from the process. Redirect forwarding had already
been disabled; direct endpoint selection remained unrestricted.

## Resolution evidence

- `src/clj_llm/http.clj` validates and normalizes every shared destination
  before JDK request construction or network I/O. It rejects malformed,
  relative, non-HTTP(S), hostless, user-info/query/fragment-bearing, and
  invalid-port URIs with safe typed data.
- `http/request-options` forwards optional provider `:endpoint-policy`.
  Anthropic, OpenAI, and Ollama use that shared path for every applicable
  generation, streaming, and embedding request.
- `test/clj_llm/http_test.clj` covers exact normalized policy input,
  loopback/private/link-local/IPv6 preservation, unusual ports, malformed
  destinations, allow/reject/failure behavior, pre-DNS rejection, no-send
  guarantees, and every applicable built-in forwarding path.
- `src/clj_llm/config.clj`, all built-in adapter docstrings, `README.md`,
  `notebooks/getting_started.clj`, `notebooks/adapters.md`,
  `notebooks/design.md`, and `CHANGELOG.md` document trusted configuration,
  local HTTP support, exact hook/error semantics, and DNS/IP-range limits.
  Generated documentation is intentionally not hand-edited.

## Attack scenario

A multi-tenant service exposes provider selection or accepts a user-authored config map. An attacker selects an internal URL or a credential-collection host. The server performs authenticated POST requests from its privileged network position, disclosing prompts, headers, or reachability information.

## Design work

1. State prominently that provider configuration and `:base-url` are trusted application configuration, never request data.
2. Validate built-in adapter URIs as absolute `http` or `https` URLs without user-info or fragments.
3. Consider an optional endpoint-policy function that applications can use to allow hosts/ports and reject loopback, link-local, private, or metadata-service ranges.
4. Resolve and re-check every address immediately before connecting if a policy promises network-range restrictions; hostname-only checks are vulnerable to DNS rebinding.
5. Preserve explicit local HTTP support for Ollama and compatible development servers.

Do not impose a blanket HTTPS rule that silently breaks local providers. Secure hosted deployments and local development need explicit, distinguishable policies.

## Acceptance criteria

- [x] Config and adapter documentation name the trusted-configuration assumption.
- [x] Malformed or non-HTTP(S) built-in endpoint URIs fail before a request is sent.
- [x] The optional policy receives the normalized destination and runs for generation, embeddings, and streaming.
- [x] Policy tests cover loopback, private/link-local addresses, IPv6, user-info, unusual ports, and hostname resolution behavior.

## Resolution

Built-in requests now normalize and validate their destination at the shared
`clj-llm.http` boundary before constructing the JDK request and before network
I/O. A destination must be an absolute `http` or `https` URI
with a host and a port in the usable range. User-info, query, and fragment
components are rejected; explicit local and private HTTP remains supported.

Provider `:endpoint-policy` is an optional application function. All built-in
generation, streaming, and embedding paths forward it through
`http/request-options`. It receives one immutable destination map:

```clojure
{:scheme "https"     ; lower-case
 :host "example.com" ; lower-case; IPv6 has no URI brackets
 :port 443           ; explicit, or effective 80/443 default
 :path "/v1/chat"}   ; normalized raw path, "/" when absent
```

A truthy return allows request construction. False or nil throws `ex-info`
with exactly `{:type :llm/endpoint-rejected}`. A policy exception is wrapped
with exactly `{:type :llm/endpoint-policy-error}` and retained as the cause.
Invalid endpoints throw `ex-info` with
`{:type :llm/invalid-endpoint :reason reason}`, where `reason` is one of
`:malformed`, `:relative`, `:unsupported-scheme`, `:missing-host`,
`:user-info`, `:query`, `:fragment`, or `:invalid-port`. A non-function policy
uses `{:type :llm/config-error :key :endpoint-policy}`. These local error maps
never include a URL, headers, credentials, prompts, or request body.

The hook receives the URI hostname without resolving it. It is therefore
appropriate for scheme/host/port/path allowlists, but it does not pin the
address used to connect and cannot safely enforce IP/network ranges against
DNS rebinding. Deployments requiring that guarantee need network egress
controls or a custom transport/provider that connects to the checked address.
