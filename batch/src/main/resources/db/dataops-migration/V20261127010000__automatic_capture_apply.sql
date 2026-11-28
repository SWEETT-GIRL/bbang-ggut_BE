-- A committed plan survives worker timeouts and never expands into the global PENDING backlog.
CREATE TABLE response_auto_apply (
    capture_id VARCHAR(64) PRIMARY KEY REFERENCES response_capture(capture_id),
    policy_version TEXT NOT NULL,
    candidates JSONB NOT NULL,
    results JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT auto_candidates_array CHECK (jsonb_typeof(candidates) = 'array'),
    CONSTRAINT auto_results_object CHECK (jsonb_typeof(results) = 'object')
);
