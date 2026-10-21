-- 기존 칩 ID와 공식 읍면동 집합의 명시적 연결. 경계 버전을 바꾸면 새 연결을 적재한다.
CREATE TABLE course_area_region_bindings (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES region_boundary_versions(id),
    course_area_id BIGINT NOT NULL REFERENCES course_area(id),
    legal_codes TEXT[] NOT NULL CHECK (cardinality(legal_codes) > 0 AND array_position(legal_codes, NULL) IS NULL),
    mapping_note TEXT NOT NULL,
    area_signature JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (version_id, course_area_id)
);
CREATE FUNCTION guard_course_area_region_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Region binding is immutable; create a new boundary version';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM course_area a WHERE a.id=NEW.course_area_id
        AND NEW.area_signature=jsonb_build_array(a.sido,a.passport_spot,a.region_2nd,a.region_3rd)) THEN
        RAISE EXCEPTION 'Course area identity differs from audited binding';
    END IF;
    IF EXISTS (SELECT code FROM unnest(NEW.legal_codes) code
        WHERE NOT EXISTS (SELECT 1 FROM region_emd_boundaries b
            WHERE b.version_id=NEW.version_id AND b.legal_code=code)) THEN
        RAISE EXCEPTION 'Binding contains unknown legal codes';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER course_area_region_binding_immutable BEFORE INSERT OR UPDATE OR DELETE ON course_area_region_bindings
    FOR EACH ROW EXECUTE FUNCTION guard_course_area_region_binding();
