-- 월말 집계에 필요한 필드만 보존한다. 사진 파일 키·메모·닉네임은 저장하지 않는다.
-- 원본 변경과 이력은 같은 트랜잭션에서 반영/롤백된다. 변경 시각은 DB에서 관측한 변경 시각(KST)이다.
LOCK TABLE bread_record, bread_record_tag, bread_record_photo, bread_record_loved_bread,
    saved_stores, store_plan, courses, course_stores, menu, menu_category IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE monthly_report_history_coverage (
    id integer PRIMARY KEY CHECK (id = 1),
    covered_from timestamp NOT NULL
);
INSERT INTO monthly_report_history_coverage VALUES (1, clock_timestamp() AT TIME ZONE 'Asia/Seoul');

CREATE TABLE monthly_report_source_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type varchar(32) NOT NULL CHECK (source_type IN
        ('BREAD_RECORD', 'BREAD_TAG', 'BREAD_PHOTO', 'SAVED_STORE', 'STORE_PLAN', 'COURSE', 'COURSE_STORE')),
    source_key varchar(100) NOT NULL,
    member_id bigint,
    parent_id bigint,
    recorded_at timestamp NOT NULL DEFAULT (clock_timestamp() AT TIME ZONE 'Asia/Seoul'),
    payload jsonb CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object')
);
CREATE INDEX idx_monthly_report_history_latest
    ON monthly_report_source_history (source_type, source_key, recorded_at DESC, id DESC);
CREATE INDEX idx_monthly_report_history_member
    ON monthly_report_source_history (member_id, source_type, recorded_at);
CREATE INDEX idx_monthly_report_history_parent
    ON monthly_report_source_history (parent_id, source_type, recorded_at);

CREATE FUNCTION monthly_report_history_emit(kind text, key text, owner_id bigint, parent bigint, data jsonb)
RETURNS void LANGUAGE sql AS $$
    INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
    VALUES (kind, key, owner_id, parent, data);
$$;

CREATE FUNCTION monthly_report_record_payload(r bread_record) RETURNS jsonb LANGUAGE sql VOLATILE AS $$
    SELECT jsonb_build_object('id', r.id, 'storeId', r.store_id, 'regionKey', r.region_key,
        'createdAt', r.created_at, 'eatenAt', r.eaten_at, 'deletedAt', r.deleted_at,
        'lovedBread', r.loved_bread, 'lovedBreads', COALESCE(
            (SELECT jsonb_agg(name ORDER BY sort_order) FROM bread_record_loved_bread WHERE record_id = r.id),
            '[]'::jsonb));
$$;
CREATE FUNCTION monthly_report_tag_payload(t bread_record_tag) RETURNS jsonb LANGUAGE sql VOLATILE AS $$
    SELECT jsonb_build_object('id', t.id, 'recordId', t.record_id, 'photoId', t.photo_id,
        'name', t.name, 'breadCategory', t.bread_category, 'menuKind',
        (SELECT mc.name FROM menu m LEFT JOIN menu_category mc ON mc.id = m.menu_category_id WHERE m.id = t.menu_id));
$$;

CREATE FUNCTION monthly_report_capture_bread_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r bread_record; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('BREAD_RECORD', old_key, r.member_id, NULL::bigint, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('BREAD_RECORD', new_key, r.member_id, NULL::bigint, monthly_report_record_payload(r));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_bread_record
    AFTER INSERT OR UPDATE OR DELETE ON bread_record
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_bread_record();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'BREAD_RECORD', r.id::text, r.member_id, NULL::bigint, monthly_report_record_payload(r) FROM bread_record r;

CREATE FUNCTION monthly_report_capture_bread_record_tag() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r bread_record_tag; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('BREAD_TAG', old_key, (SELECT member_id FROM bread_record WHERE id = r.record_id), r.record_id, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('BREAD_TAG', new_key, (SELECT member_id FROM bread_record WHERE id = r.record_id), r.record_id, monthly_report_tag_payload(r));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_bread_record_tag
    AFTER INSERT OR UPDATE OR DELETE ON bread_record_tag
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_bread_record_tag();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'BREAD_TAG', r.id::text, (SELECT member_id FROM bread_record WHERE id = r.record_id), r.record_id, monthly_report_tag_payload(r) FROM bread_record_tag r;

CREATE FUNCTION monthly_report_capture_bread_record_photo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r bread_record_photo; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('BREAD_PHOTO', old_key, (SELECT member_id FROM bread_record WHERE id = r.record_id), r.record_id, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('BREAD_PHOTO', new_key, (SELECT member_id FROM bread_record WHERE id = r.record_id), r.record_id, jsonb_build_object('id', r.id, 'recordId', r.record_id, 'referenceId', r.reference_id, 'sortOrder', r.sort_order));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_bread_record_photo
    AFTER INSERT OR UPDATE OR DELETE ON bread_record_photo
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_bread_record_photo();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'BREAD_PHOTO', r.id::text, (SELECT member_id FROM bread_record WHERE id = r.record_id), r.record_id, jsonb_build_object('id', r.id, 'recordId', r.record_id, 'referenceId', r.reference_id, 'sortOrder', r.sort_order) FROM bread_record_photo r;

CREATE FUNCTION monthly_report_capture_saved_stores() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r saved_stores; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('SAVED_STORE', old_key, r.member_id, NULL::bigint, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('SAVED_STORE', new_key, r.member_id, NULL::bigint, jsonb_build_object('storeId', r.store_id, 'createdAt', r.created_at));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_saved_stores
    AFTER INSERT OR UPDATE OR DELETE ON saved_stores
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_saved_stores();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'SAVED_STORE', r.id::text, r.member_id, NULL::bigint, jsonb_build_object('storeId', r.store_id, 'createdAt', r.created_at) FROM saved_stores r;

CREATE FUNCTION monthly_report_capture_store_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r store_plan; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.member_id::text || ':' || r.store_id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.member_id::text || ':' || r.store_id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('STORE_PLAN', old_key, r.member_id, NULL::bigint, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('STORE_PLAN', new_key, r.member_id, NULL::bigint, jsonb_build_object('storeId', r.store_id, 'createdAt', r.created_at));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_store_plan
    AFTER INSERT OR UPDATE OR DELETE ON store_plan
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_store_plan();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'STORE_PLAN', r.member_id::text || ':' || r.store_id::text, r.member_id, NULL::bigint, jsonb_build_object('storeId', r.store_id, 'createdAt', r.created_at) FROM store_plan r;

CREATE FUNCTION monthly_report_capture_courses() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r courses; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('COURSE', old_key, r.member_id, NULL::bigint, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('COURSE', new_key, r.member_id, NULL::bigint, jsonb_build_object('id', r.id, 'createdAt', r.created_at, 'deletedAt', r.deleted_at));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_courses
    AFTER INSERT OR UPDATE OR DELETE ON courses
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_courses();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'COURSE', r.id::text, r.member_id, NULL::bigint, jsonb_build_object('id', r.id, 'createdAt', r.created_at, 'deletedAt', r.deleted_at) FROM courses r;

CREATE FUNCTION monthly_report_capture_course_stores() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r course_stores; old_key text; new_key text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        r := OLD;
        old_key := r.id::text;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        new_key := r.id::text;
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND old_key <> new_key) THEN
        r := OLD;
        PERFORM monthly_report_history_emit('COURSE_STORE', old_key, (SELECT member_id FROM courses WHERE id = r.course_id), r.course_id, NULL);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        r := NEW;
        PERFORM monthly_report_history_emit('COURSE_STORE', new_key, (SELECT member_id FROM courses WHERE id = r.course_id), r.course_id, jsonb_build_object('storeId', r.store_id, 'createdAt', r.created_at, 'deletedAt', r.deleted_at));
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_course_stores
    AFTER INSERT OR UPDATE OR DELETE ON course_stores
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_course_stores();

INSERT INTO monthly_report_source_history (source_type, source_key, member_id, parent_id, payload)
SELECT 'COURSE_STORE', r.id::text, (SELECT member_id FROM courses WHERE id = r.course_id), r.course_id, jsonb_build_object('storeId', r.store_id, 'createdAt', r.created_at, 'deletedAt', r.deleted_at) FROM course_stores r;

-- 최고였던 빵 목록은 별도 컬렉션 테이블이므로 부모 행 변경 없이 바뀌는 경우도 보존한다.
CREATE FUNCTION monthly_report_capture_loved_bread() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE record_key bigint; r bread_record;
BEGIN
    FOR record_key IN SELECT DISTINCT unnest(ARRAY[
        CASE WHEN TG_OP <> 'INSERT' THEN OLD.record_id END,
        CASE WHEN TG_OP <> 'DELETE' THEN NEW.record_id END])
    LOOP
        SELECT * INTO r FROM bread_record WHERE id = record_key;
        IF FOUND THEN
            PERFORM monthly_report_history_emit('BREAD_RECORD', r.id::text, r.member_id, NULL,
                monthly_report_record_payload(r));
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_loved_bread
    AFTER INSERT OR UPDATE OR DELETE ON bread_record_loved_bread
    FOR EACH ROW EXECUTE FUNCTION monthly_report_capture_loved_bread();

-- 분류 계산 규칙은 Java가 담당한다. 여기서는 당시 룰 분류와 메뉴 분류 사실만 보존한다.
CREATE FUNCTION monthly_report_capture_menu_category_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t bread_record_tag; owner_id bigint;
BEGIN
    FOR t IN
        SELECT tag.* FROM bread_record_tag tag JOIN menu m ON m.id = tag.menu_id
        WHERE (TG_TABLE_NAME = 'menu' AND m.id = NEW.id)
           OR (TG_TABLE_NAME = 'menu_category' AND m.menu_category_id = NEW.id)
    LOOP
        SELECT member_id INTO owner_id FROM bread_record WHERE id = t.record_id;
        PERFORM monthly_report_history_emit('BREAD_TAG', t.id::text, owner_id, t.record_id,
            monthly_report_tag_payload(t));
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_history_menu_category
    AFTER UPDATE OF menu_category_id ON menu
    FOR EACH ROW WHEN (OLD.menu_category_id IS DISTINCT FROM NEW.menu_category_id)
    EXECUTE FUNCTION monthly_report_capture_menu_category_change();
CREATE TRIGGER monthly_report_history_category_name
    AFTER UPDATE OF name ON menu_category
    FOR EACH ROW WHEN (OLD.name IS DISTINCT FROM NEW.name)
    EXECUTE FUNCTION monthly_report_capture_menu_category_change();
