# Chat model notes (Llama 3 family, Mistral)

Status: **no model has been run against this service.** The authoring environment has no Ollama, GPU or model weights. Everything
here is configuration and risk analysis; treat model quality claims as hypotheses to test.

## Selecting a model
`DPI_CHAT_MODEL` (Helm `config.chatModel`) is the only switch; the embedding model stays BGE-M3 (`bge-m3`, 1024 dimensions).
Candidate Ollama tags — **verify each exists in your Ollama library/registry before relying on it**: `llama3.1:8b`, `llama3:8b`, `mistral:7b`.
Switching the chat model needs no re-embedding (only the embedding model/version does).

## Settings that matter for these models
- `DPI_CHAT_NUM_CTX` (default 8192): Ollama's own default window is much smaller and silently truncates the prompt, which would drop evidence
  without any error. Never set it above what the model supports (original Llama 3 8B: 8k; Llama 3.1 and recent Mistral releases: larger — confirm on the model card).
  The prompt carries up to 8 log chunks + 3 knowledge chunks; larger values need more GPU/CPU memory.
- `DPI_CHAT_NUM_PREDICT` (default 1024): caps output; a root-cause JSON longer than this is cut off and fails parsing (→ analysis ends `FAILED`).
- Temperature is 0.1.

## Main risk: structured output from 7–8B models
The agent needs valid JSON for query analysis and root-cause output (Spring AI `entity(...)`). Small models can wrap JSON in prose or fences,
invent field values or ignore the schema. The service does not repair output: a parse failure ends the analysis as `FAILED` (visible in
`dpi_llm_call_seconds_count{result="error"}`) and the verifier discards unsupported claims, so the failure mode is "no answer", not "wrong answer".
Test before adopting a model: run 30–50 real questions and record (a) parse failure rate, (b) share of claims rejected by the verifier, (c) engineer-judged correctness.
Suggested acceptance bar to agree with the team (not derived from data): parse failures < 5%, and no unsupported claim reaching a report.

## Comparison to run (expected, unmeasured)
| | Llama 3 / 3.1 8B | Mistral 7B |
|---|---|---|
| Context window | check model card | check model card |
| JSON adherence | to be measured | to be measured |
| Licence for your use | check Meta licence terms | check the specific release's licence |
