-- Frozen, local-only evidence used by both profile and label builds.
-- Existing rows intentionally remain NULL: an old draft is not a verified run.
ALTER TABLE store_profile_draft ADD COLUMN kind_evidence JSONB;
ALTER TABLE store_profile_draft ADD COLUMN kind_evidence_fingerprint VARCHAR(64);
