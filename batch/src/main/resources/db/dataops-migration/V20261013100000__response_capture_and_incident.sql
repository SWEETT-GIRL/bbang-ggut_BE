-- Commit input independently of parsing and downstream processing; no serving tables.
CREATE TABLE response_capture (
    capture_id VARCHAR(64) PRIMARY KEY,
    request_digest CHAR(64) NOT NULL,
    store_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL,
    external_id TEXT,
    observed_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_seq BIGINT,
    payload TEXT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    parser_digest TEXT,
    contract_version TEXT,
    diagnostics TEXT,
    CONSTRAINT response_capture_status CHECK (status IN ('RECEIVED', 'READY', 'HELD'))
);
CREATE INDEX response_capture_store ON response_capture (store_id, source, received_at DESC);
CREATE TABLE response_incident (
    fingerprint CHAR(64) PRIMARY KEY,
    source VARCHAR(20) NOT NULL,
    parser_digest TEXT NOT NULL,
    contract_version TEXT NOT NULL,
    diagnostics TEXT NOT NULL,
    model_eligible BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE response_incident_sample (
    fingerprint CHAR(64) NOT NULL REFERENCES response_incident(fingerprint),
    capture_id VARCHAR(64) NOT NULL REFERENCES response_capture(capture_id),
    PRIMARY KEY (fingerprint, capture_id)
);
COMMENT ON TABLE response_capture IS 'Immutable input. Assessment is not processing completion or apply permission.';
