-- Baseline schema. Requires the pgvector extension (CREATE EXTENSION needs a privileged role;
-- in production run it once as an admin and keep the application role least-privilege).
-- Embedding dimension 1024 matches BGE-M3 (ASSUMPTION: verify against the deployed model; changing the
-- model/dimension requires a new migration + re-embedding, never mixing vectors in one column).
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------- collection 1: log chunks
CREATE TABLE log_chunk (
    chunk_id          CHAR(32)      PRIMARY KEY,           -- deterministic (SHA-256 prefix of source ids)
    service           TEXT          NOT NULL,
    level             TEXT          NOT NULL,
    from_ts           TIMESTAMPTZ   NOT NULL,
    to_ts             TIMESTAMPTZ   NOT NULL,
    error_code        TEXT          NOT NULL DEFAULT '',
    trace_id          TEXT          NOT NULL DEFAULT '',
    host              TEXT          NOT NULL DEFAULT '',
    content           TEXT          NOT NULL,              -- sanitized (masked) content only
    content_sha256    CHAR(64)      NOT NULL,              -- duplicate-embedding guard
    source_ids        TEXT[]        NOT NULL,
    metadata          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    embedding         vector(1024)  NOT NULL,
    embedding_model   TEXT          NOT NULL,
    embedding_version TEXT          NOT NULL,
    ingested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_log_chunk_range CHECK (to_ts >= from_ts)
);
CREATE INDEX idx_log_chunk_embedding_hnsw ON log_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_log_chunk_service_from  ON log_chunk (service, from_ts DESC);
CREATE INDEX idx_log_chunk_level_from    ON log_chunk (level, from_ts DESC);
CREATE INDEX idx_log_chunk_error_code    ON log_chunk (error_code) WHERE error_code <> '';
CREATE INDEX idx_log_chunk_trace_id      ON log_chunk (trace_id)   WHERE trace_id <> '';
CREATE INDEX idx_log_chunk_from_ts       ON log_chunk (from_ts DESC);
CREATE INDEX idx_log_chunk_source_ids    ON log_chunk USING gin (source_ids);
CREATE INDEX idx_log_chunk_model         ON log_chunk (embedding_model, embedding_version);
CREATE UNIQUE INDEX uq_log_chunk_content_model ON log_chunk (content_sha256, embedding_model, embedding_version);

-- ---------------------------------------------------------------- collection 2: runbooks / incidents
CREATE TABLE knowledge_document (
    doc_id            UUID          PRIMARY KEY,
    doc_key           TEXT          NOT NULL,              -- stable logical id (e.g. runbook slug)
    version           INTEGER       NOT NULL,
    kind              TEXT          NOT NULL,              -- RUNBOOK | INCIDENT | PROCEDURE
    title             TEXT          NOT NULL,
    source_ref        TEXT          NOT NULL,              -- URL / path / ticket reference
    validation_status TEXT          NOT NULL DEFAULT 'UNVERIFIED', -- UNVERIFIED | ENGINEER_VALIDATED
    validated_by      TEXT,
    validated_at      TIMESTAMPTZ,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_doc_version UNIQUE (doc_key, version),
    CONSTRAINT chk_kind CHECK (kind IN ('RUNBOOK', 'INCIDENT', 'PROCEDURE')),
    CONSTRAINT chk_validation CHECK (validation_status IN ('UNVERIFIED', 'ENGINEER_VALIDATED')),
    CONSTRAINT chk_validated_has_actor CHECK (validation_status <> 'ENGINEER_VALIDATED' OR validated_by IS NOT NULL)
);

CREATE TABLE knowledge_chunk (
    chunk_id          UUID          PRIMARY KEY,
    doc_id            UUID          NOT NULL REFERENCES knowledge_document (doc_id) ON DELETE CASCADE,
    chunk_index       INTEGER       NOT NULL,
    content           TEXT          NOT NULL,              -- sanitized content only
    content_sha256    CHAR(64)      NOT NULL,
    metadata          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    embedding         vector(1024)  NOT NULL,
    embedding_model   TEXT          NOT NULL,
    embedding_version TEXT          NOT NULL,
    CONSTRAINT uq_knowledge_chunk UNIQUE (doc_id, chunk_index)
);
CREATE INDEX idx_knowledge_chunk_embedding_hnsw ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_knowledge_chunk_doc   ON knowledge_chunk (doc_id);
CREATE INDEX idx_knowledge_chunk_model ON knowledge_chunk (embedding_model, embedding_version);
CREATE INDEX idx_knowledge_doc_kind    ON knowledge_document (kind, active);

-- ---------------------------------------------------------------- ingestion state
CREATE TABLE ingestion_checkpoint (
    stream_name       TEXT          PRIMARY KEY,           -- e.g. index pattern
    last_timestamp    TIMESTAMPTZ   NOT NULL,
    last_sort_values  JSONB         NOT NULL,              -- search_after tuple of last persisted record
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE ingestion_run (
    run_id            UUID          PRIMARY KEY,
    stream_name       TEXT          NOT NULL,
    status            TEXT          NOT NULL,              -- RUNNING | SUCCEEDED | FAILED | PARTIAL
    started_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    records_read      BIGINT        NOT NULL DEFAULT 0,
    records_stored    BIGINT        NOT NULL DEFAULT 0,
    records_dead      BIGINT        NOT NULL DEFAULT 0,
    error_summary     TEXT
);

CREATE TABLE ingestion_dead_letter (
    id                BIGSERIAL     PRIMARY KEY,
    stream_name       TEXT          NOT NULL,
    es_doc_id         TEXT          NOT NULL,
    es_index          TEXT          NOT NULL DEFAULT '',
    failure_stage     TEXT          NOT NULL,              -- VALIDATE | MASK | CHUNK | EMBED | STORE
    failure_reason    TEXT          NOT NULL,              -- never contains raw log content
    attempts          INTEGER       NOT NULL,
    first_failed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_failed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved          BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_dead_letter UNIQUE (stream_name, es_doc_id)
);

-- ---------------------------------------------------------------- analysis (agent) state
CREATE TABLE analysis_request (
    analysis_id       UUID          PRIMARY KEY,
    query_text        TEXT          NOT NULL,
    requested_by      TEXT          NOT NULL,
    correlation_id    TEXT          NOT NULL,
    status            TEXT          NOT NULL,              -- QUEUED | RUNNING | AWAITING_REVIEW | COMPLETED | INSUFFICIENT_EVIDENCE | FAILED | TIMED_OUT
    failure_reason    TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_analysis_request_status ON analysis_request (status, created_at DESC);

CREATE TABLE agent_checkpoint (
    analysis_id       UUID          NOT NULL REFERENCES analysis_request (analysis_id) ON DELETE CASCADE,
    seq               INTEGER       NOT NULL,
    node_name         TEXT          NOT NULL,
    state_json        JSONB         NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (analysis_id, seq)
);

CREATE TABLE incident_report (
    analysis_id       UUID          PRIMARY KEY REFERENCES analysis_request (analysis_id) ON DELETE CASCADE,
    report_json       JSONB         NOT NULL,
    review_status     TEXT          NOT NULL DEFAULT 'PENDING_REVIEW', -- PENDING_REVIEW | APPROVED | REJECTED | NEEDS_MORE_INFO
    reviewed_by       TEXT,
    reviewed_at       TIMESTAMPTZ,
    review_comment    TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_incident_report_review ON incident_report (review_status, created_at DESC);

CREATE TABLE audit_log (
    id                BIGSERIAL     PRIMARY KEY,
    at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor             TEXT          NOT NULL,
    action            TEXT          NOT NULL,
    subject           TEXT          NOT NULL,
    correlation_id    TEXT          NOT NULL,
    detail            JSONB         NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_audit_log_at ON audit_log (at DESC);
