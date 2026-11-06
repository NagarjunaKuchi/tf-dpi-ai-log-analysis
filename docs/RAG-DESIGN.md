# RAG pipeline design (Spring AI reference)

Source: https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html

## Artifacts (from the page)
- `spring-ai-rag` — `RetrievalAugmentationAdvisor`. The page does not say which artifact holds
  `VectorStoreDocumentRetriever`, the query transformers, `ConcatenationDocumentJoiner` or
  `ContextualQueryAugmenter`; presumed `spring-ai-rag` — **verify at first build**.
- `spring-ai-vector-store-advisor` — `QuestionAnswerAdvisor`.

## Component mapping to the LangGraph4j graph
| Graph step | Spring AI component |
|------------|---------------------|
| Retrieval (`vectorSearch`, `runbookSearch`) | `VectorStoreDocumentRetriever` (`topK`, `similarityThreshold`, `filterExpression`); one retriever per collection |
| Metadata filters (service, level, time, error code, trace id) | `FilterExpressionBuilder`, passed per request via `Query.context(VectorStoreDocumentRetriever.FILTER_EXPRESSION)` (per-request filter takes precedence) |
| Query rewrite node | `RewriteQueryTransformer` (low temperature, per the page) |
| Multi-angle retrieval | `MultiQueryExpander` + `ConcatenationDocumentJoiner` (first occurrence kept on duplicates) |
| Context assembly | explicit evidence list built by our code (see decision below) |

## Decision: components inside graph nodes, not an end-to-end advisor
`RetrievalAugmentationAdvisor` hides retrieval inside the LLM call. Our graph must grade relevance, count bounded
retries, and cite evidence ids, so retrieval runs as explicit graph steps using the retriever and transformers
directly. `ChatClient` is used for query analysis, root-cause analysis and report drafting, with evidence passed in
as clearly delimited untrusted data. Empty retrieval short-circuits to "insufficient evidence" before any LLM call;
the augmenter's default (`allowEmptyContext=false`) matches that stance.

## OPEN: Spring AI version
The page documents **Spring AI 2.0.2**. `pom.xml` pins 1.0.3 with Boot 3.5.6. The page does not state which Spring
Boot / Framework baseline 2.x needs, and the brief says "Spring Boot 3.x". Decide the Spring AI line, then align Boot
and re-check the builder APIs above against that version before coding the agent.
