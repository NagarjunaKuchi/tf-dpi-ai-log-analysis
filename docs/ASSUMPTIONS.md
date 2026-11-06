# Assumptions and design decisions

The presentation ("AI-POC-DPITeam_Enhanced.pptx") is the source of truth. Where it is silent, the choice below
is recorded here. None of these changes the approved architecture.

| # | Topic | Decision | Reason / source |
|---|-------|----------|-----------------|
| 1 | Package root | `tf.dpi.ai` (Maven groupId `tf.dpi.ai`) | Requested by the project owner |
| 2 | Java / Boot | Java 21, Spring Boot 3.x | Deck says "Java 17 / 21"; the brief says 21 |
| 3 | Build tool | Maven | Deck says "Maven / Gradle"; the brief says Maven |
| 4 | Embedding dimension | 1024 (BGE-M3) | **Unverified**: confirm against the deployed model before first migration |
| 5 | MOSIP identifiers | UIN = 10 digits, VID = 16 digits; bare numbers of those lengths are masked | Not in the deck. Over-masks some epoch-second timestamps and phone numbers on purpose |
| 6 | Token estimate | ~4 characters per token | Heuristic; avoids a tokenizer dependency. Budget leaves headroom below the model limit |
| 7 | Grouping key | trace id if present, else service + host + (error code or exception type) | Deck: "by service, trace and time" and "service, code or time" |
| 8 | Runbook trust | Documents default to `UNVERIFIED`; only `ENGINEER_VALIDATED` documents may be cited as established fixes | Brief: unverified AI-generated resolutions are not established fixes |
| 9 | Stack-trace overflow | A single event over budget keeps all exception headers and "Caused by" lines, elides deep frames, flags `truncated=true` | Brief: keep chains whole and avoid oversized chunks |
| 10 | Checkpoint | Stored in PostgreSQL with the `search_after` tuple of the last persisted record, committed in the same transaction as the chunk writes | Brief: never advance beyond persisted records |
