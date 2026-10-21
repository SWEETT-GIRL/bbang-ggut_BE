-- Legacy 제보 데이터를 ContributionReport 구조로 이관하고 알림 신규 참조 컬럼을 채운다.
-- 기존 report_id/report_kind는 API 호환 필드로 유지한다.

DO $$
DECLARE
    legacy_store_report RECORD;
    legacy_store_add_report RECORD;
    legacy_menu_add_report RECORD;
    new_contribution_report_id BIGINT;
BEGIN
    FOR legacy_store_report IN
        SELECT sr.*
        FROM store_report sr
        WHERE NOT EXISTS (
            SELECT 1
            FROM store_contribution_report scr
            WHERE scr.store_report_id = sr.id
        )
          AND NOT EXISTS (
            SELECT 1
            FROM menu_contribution_report mcr
            WHERE mcr.store_report_id = sr.id
        )
    LOOP
        INSERT INTO contribution_report (
            member_id,
            target_type,
            report_type,
            status,
            reviewed_at,
            created_at,
            updated_at
        )
        VALUES (
            legacy_store_report.member_id,
            CASE
                WHEN legacy_store_report.request_type = 'MOD_BREAD' THEN 'MENU'
                ELSE 'STORE'
            END,
            'UPDATE',
            legacy_store_report.status,
            legacy_store_report.reviewed_at,
            legacy_store_report.created_at,
            COALESCE(legacy_store_report.updated_at, legacy_store_report.created_at)
        )
        RETURNING id INTO new_contribution_report_id;

        IF legacy_store_report.request_type = 'MOD_BREAD' THEN
            INSERT INTO menu_contribution_report (
                contribution_report_id,
                store_report_id,
                created_at,
                updated_at
            )
            VALUES (
                new_contribution_report_id,
                legacy_store_report.id,
                legacy_store_report.created_at,
                COALESCE(legacy_store_report.updated_at, legacy_store_report.created_at)
            );
        ELSE
            INSERT INTO store_contribution_report (
                contribution_report_id,
                store_report_id,
                created_at,
                updated_at
            )
            VALUES (
                new_contribution_report_id,
                legacy_store_report.id,
                legacy_store_report.created_at,
                COALESCE(legacy_store_report.updated_at, legacy_store_report.created_at)
            );
        END IF;
    END LOOP;

    FOR legacy_store_add_report IN
        SELECT sar.*
        FROM store_add_report sar
        WHERE NOT EXISTS (
            SELECT 1
            FROM store_contribution_report scr
            WHERE scr.store_add_report_id = sar.id
        )
    LOOP
        INSERT INTO contribution_report (
            member_id,
            target_type,
            report_type,
            status,
            reviewed_at,
            created_at,
            updated_at
        )
        VALUES (
            legacy_store_add_report.member_id,
            'STORE',
            'ADD',
            legacy_store_add_report.status,
            legacy_store_add_report.reviewed_at,
            legacy_store_add_report.created_at,
            COALESCE(legacy_store_add_report.updated_at, legacy_store_add_report.created_at)
        )
        RETURNING id INTO new_contribution_report_id;

        INSERT INTO store_contribution_report (
            contribution_report_id,
            store_add_report_id,
            created_at,
            updated_at
        )
        VALUES (
            new_contribution_report_id,
            legacy_store_add_report.id,
            legacy_store_add_report.created_at,
            COALESCE(legacy_store_add_report.updated_at, legacy_store_add_report.created_at)
        );
    END LOOP;

    FOR legacy_menu_add_report IN
        SELECT mar.*
        FROM menu_add_report mar
        WHERE NOT EXISTS (
            SELECT 1
            FROM menu_contribution_report mcr
            WHERE mcr.menu_add_report_id = mar.id
        )
    LOOP
        INSERT INTO contribution_report (
            member_id,
            target_type,
            report_type,
            status,
            reviewed_at,
            created_at,
            updated_at
        )
        VALUES (
            legacy_menu_add_report.member_id,
            'MENU',
            'ADD',
            legacy_menu_add_report.status,
            legacy_menu_add_report.reviewed_at,
            legacy_menu_add_report.created_at,
            COALESCE(legacy_menu_add_report.updated_at, legacy_menu_add_report.created_at)
        )
        RETURNING id INTO new_contribution_report_id;

        INSERT INTO menu_contribution_report (
            contribution_report_id,
            menu_add_report_id,
            created_at,
            updated_at
        )
        VALUES (
            new_contribution_report_id,
            legacy_menu_add_report.id,
            legacy_menu_add_report.created_at,
            COALESCE(legacy_menu_add_report.updated_at, legacy_menu_add_report.created_at)
        );
    END LOOP;
END $$;

ALTER TABLE report_notification
    ADD COLUMN IF NOT EXISTS contribution_report_id BIGINT REFERENCES contribution_report (id),
    ADD COLUMN IF NOT EXISTS abuse_report_id BIGINT REFERENCES abuse_report (id);

UPDATE report_notification rn
SET abuse_report_id = rn.report_id
WHERE rn.report_kind = 'ABUSE_REPORT'
  AND rn.abuse_report_id IS NULL
  AND EXISTS (
      SELECT 1
      FROM abuse_report ar
      WHERE ar.id = rn.report_id
  );

UPDATE report_notification rn
SET contribution_report_id = scr.contribution_report_id
FROM store_contribution_report scr
WHERE rn.report_kind = 'STORE_ADD_REPORT'
  AND rn.report_id = scr.store_add_report_id
  AND rn.contribution_report_id IS NULL;

UPDATE report_notification rn
SET contribution_report_id = mcr.contribution_report_id
FROM menu_contribution_report mcr
WHERE rn.report_kind = 'MENU_ADD_REPORT'
  AND rn.report_id = mcr.menu_add_report_id
  AND rn.contribution_report_id IS NULL;

UPDATE report_notification rn
SET contribution_report_id = scr.contribution_report_id
FROM store_contribution_report scr
WHERE rn.report_kind = 'STORE_REPORT'
  AND rn.report_id = scr.store_report_id
  AND rn.contribution_report_id IS NULL;

UPDATE report_notification rn
SET contribution_report_id = mcr.contribution_report_id
FROM menu_contribution_report mcr
WHERE rn.report_kind = 'STORE_REPORT'
  AND rn.report_id = mcr.store_report_id
  AND rn.contribution_report_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_report_notification_contribution_report
    ON report_notification (contribution_report_id)
    WHERE contribution_report_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_report_notification_abuse_report
    ON report_notification (abuse_report_id)
    WHERE abuse_report_id IS NOT NULL;
