# Security assessment (self-assessment, not an independent review)

Scope: this repository's code and deployment artifacts. No penetration test, dependency CVE scan or threat-model workshop has been performed.
Do not treat this as certification.

## Controls implemented (and how far each is verified)
| Area | Control | Verification |
|---|---|---|
| Data protection | Ordered regex masking + structured-JSON masking before chunking, embedding, storage and LLM calls; residual detectors → fail closed | 24 unit tests + e2e (email in question/evidence never reaches stored text or report). Rules are generic: **must be validated on real MOSIP logs** (UIN/VID lengths assumed) |
| Elasticsearch | Read-only port (`LogSource` has no write operations); search-only client | Unit tests; use a read-only ES role as a second layer |
| Prompt injection | Evidence delimited and declared untrusted; LLM cannot choose tools/arguments; output gated by deterministic verifier; no automated remediation | Verifier + e2e tests with a scripted model. **Not tested against a real model or adversarial log content** |
| Hallucination | Findings must cite retrieved evidence, quote it verbatim, match scenario signatures; knowledge cannot prove current faults | Unit + e2e |
| Human gate | Review decision recorded once (atomic UPDATE), audited | PostgreSQL integration test |
| AuthN/Z | Stateless JWT, role-based endpoint rules, `denyAll` default, fails to start without an issuer | Full-context test with simulated authorities; token checks (signature, `alg:none`, HS256 confusion, wrong key/issuer/audience, expiry, missing `sub`/`aud`) unit-tested with real RSA-signed JWTs; end-to-end smoke test against a real Keycloak 26.8.2 (no/garbage/tampered/foreign-realm tokens → 401, role enforcement → 403) run **locally with a single instance**. Not tested: production Keycloak setup, token revocation/logout (access tokens stay valid until expiry, 15 min in the dev realm), MFA, user lifecycle |
| Abuse | Per-principal rate limit (per instance), bounded request sizes, bounded queues/retries/timeouts | Unit + context tests |
| Errors/logging | problem+json with fixed messages; logs contain exception types and ids, not content; JSON logs | Context test; no log-content scanning was done |
| Container/K8s | Non-root, read-only root FS, all caps dropped, seccomp, default-deny NetworkPolicy, no token automount, management port separated | Schema-validated only; **never run** |
| Secrets | Env/Secret references only, nothing committed | `.env.example` has placeholders; no secret scanning tool run |

## Known weaknesses / risks
1. LLM output is untrusted; a determined adversary controlling log text could still steer a *correct-looking* finding. The human reviewer is the last line.
2. Masking is pattern-based: unknown formats of sensitive data (names, addresses, free text) can pass through to the vector store and LLM. Self-hosted LLM limits exposure but does not remove it.
3. Prometheus endpoint on 8081 is unauthenticated by design; protection relies on the NetworkPolicy. Clusters without NetworkPolicy enforcement expose it.
4. Rate limiting is per pod, in memory; scaling out raises the effective limit.
5. No per-tenant isolation: any ANALYST/REVIEWER can read any analysis; reviewers may approve their own submissions.
6. LangGraph4j state uses Java serialization in memory only today; if a persistent saver is added later, deserialization of attacker-writable rows must be prevented.
7. Egress NetworkPolicy defaults allow the listed ports to any destination; tighten to real destinations.
8. Dependencies were not scanned for vulnerabilities; Spring Boot 4.0.9 / Spring AI 2.0.2 are recent and unvetted by us. Run SCA (e.g. OWASP Dependency-Check, Trivy) in CI, and scan the final image.
9. Audit log is in the same database as the data; it is not tamper-evident.
10. TLS to PostgreSQL, Elasticsearch and the LLM endpoint is a configuration matter (URLs/CA); nothing here enforces it.
