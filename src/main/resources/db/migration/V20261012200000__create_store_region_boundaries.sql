-- #742: 표시 문자열을 보존하며 법정 읍면동 판정과 앱 지역 판정을 분리한다.
CREATE TABLE region_boundary_versions (
    id BIGSERIAL PRIMARY KEY,
    version_key TEXT NOT NULL UNIQUE,
    source_date DATE NOT NULL,
    reference_sha256 VARCHAR(64) NOT NULL CHECK (reference_sha256 ~ '^[0-9a-f]{64}$'),
    projection_version TEXT NOT NULL,
    metadata JSONB NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE region_app_scopes (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES region_boundary_versions(id),
    scope_code VARCHAR(10) NOT NULL CHECK (scope_code ~ '^[0-9]{10}$'),
    name TEXT NOT NULL,
    level VARCHAR(10) NOT NULL CHECK (level IN ('CITY', 'EMD')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (version_id, scope_code)
);

CREATE TABLE region_emd_boundaries (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES region_boundary_versions(id),
    legal_code VARCHAR(10) NOT NULL CHECK (legal_code ~ '^[0-9]{10}$'),
    official_name TEXT NOT NULL,
    ancestry JSONB NOT NULL,
    scope_code VARCHAR(10) NOT NULL,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (version_id, legal_code),
    FOREIGN KEY (version_id, scope_code) REFERENCES region_app_scopes(version_id, scope_code),
    CHECK (ST_IsValid(geom) AND NOT ST_IsEmpty(geom)),
    CHECK (ST_CoveredBy(ST_Envelope(geom), ST_MakeEnvelope(120,30,140,40,4326)))
);
CREATE INDEX region_emd_boundaries_geom_idx ON region_emd_boundaries USING gist(geom);

CREATE TABLE store_region_resolutions (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    version_id BIGINT NOT NULL REFERENCES region_boundary_versions(id),
    query_location geometry(Point, 4326),
    legal_codes TEXT[] NOT NULL,
    scope_codes TEXT[] NOT NULL,
    scope_code VARCHAR(10),
    legal_status VARCHAR(12) NOT NULL CHECK (legal_status IN ('NO_POINT', 'NO_MATCH', 'RESOLVED', 'AMBIGUOUS')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, version_id),
    FOREIGN KEY (version_id, scope_code) REFERENCES region_app_scopes(version_id, scope_code),
    CHECK (array_position(legal_codes, NULL) IS NULL AND array_position(scope_codes, NULL) IS NULL),
    CHECK ((cardinality(scope_codes) = 1 AND scope_code IS NOT NULL AND scope_code = scope_codes[1])
        OR (cardinality(scope_codes) <> 1 AND scope_code IS NULL)),
    CHECK ((legal_status = 'NO_POINT' AND (query_location IS NULL OR ST_IsEmpty(query_location)) AND cardinality(legal_codes) = 0)
        OR (legal_status = 'NO_MATCH' AND query_location IS NOT NULL AND NOT ST_IsEmpty(query_location) AND cardinality(legal_codes) = 0)
        OR (legal_status = 'RESOLVED' AND cardinality(legal_codes) = 1)
        OR (legal_status = 'AMBIGUOUS' AND cardinality(legal_codes) > 1))
);
CREATE INDEX store_region_resolutions_scope_idx ON store_region_resolutions(version_id, scope_code, store_id)
    WHERE scope_code IS NOT NULL;

-- 버전 확정 후 geometry/투영을 바꾸면 같은 버전의 판정을 신뢰할 수 없다.
CREATE FUNCTION guard_region_boundary_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ready THEN
        RAISE EXCEPTION 'Ready boundary version is immutable; create a new version';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.ready AND NOT EXISTS (SELECT 1 FROM region_emd_boundaries WHERE version_id=OLD.id) THEN
        RAISE EXCEPTION 'Cannot seal a boundary version without boundaries';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER region_boundary_version_immutable BEFORE UPDATE OR DELETE ON region_boundary_versions
    FOR EACH ROW EXECUTE FUNCTION guard_region_boundary_version();

CREATE FUNCTION guard_region_boundary_content() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_version BIGINT;
    is_ready BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.version_id <> OLD.version_id THEN
        RAISE EXCEPTION 'Boundary content cannot move between versions';
    END IF;
    IF TG_OP = 'DELETE' THEN target_version := OLD.version_id;
    ELSE target_version := NEW.version_id; END IF;
    -- 적재와 READY 전환이 경합해도 확정된 내용이 뒤늦게 변경되지 않도록 같은 행을 잠근다.
    SELECT ready INTO is_ready FROM region_boundary_versions WHERE id = target_version FOR SHARE;
    IF is_ready THEN RAISE EXCEPTION 'Ready boundary content is immutable'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER region_app_scope_immutable BEFORE INSERT OR UPDATE OR DELETE ON region_app_scopes
    FOR EACH ROW EXECUTE FUNCTION guard_region_boundary_content();
CREATE TRIGGER region_emd_boundary_immutable BEFORE INSERT OR UPDATE OR DELETE ON region_emd_boundaries
    FOR EACH ROW EXECUTE FUNCTION guard_region_boundary_content();
