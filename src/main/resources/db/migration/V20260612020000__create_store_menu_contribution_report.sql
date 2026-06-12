CREATE TABLE IF NOT EXISTS store_contribution_report (
    id BIGSERIAL PRIMARY KEY,
    contribution_report_id BIGINT NOT NULL REFERENCES contribution_report (id),
    store_report_id BIGINT REFERENCES store_report (id),
    store_add_report_id BIGINT REFERENCES store_add_report (id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_store_contribution_report_contribution_report UNIQUE (contribution_report_id),
    CONSTRAINT uq_store_contribution_report_store_report UNIQUE (store_report_id),
    CONSTRAINT uq_store_contribution_report_store_add_report UNIQUE (store_add_report_id),
    CONSTRAINT chk_store_contribution_report_one_legacy_ref CHECK (
        (store_report_id IS NOT NULL AND store_add_report_id IS NULL)
        OR (store_report_id IS NULL AND store_add_report_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_store_contribution_report_store_report_id
    ON store_contribution_report (store_report_id)
    WHERE store_report_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_store_contribution_report_store_add_report_id
    ON store_contribution_report (store_add_report_id)
    WHERE store_add_report_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS menu_contribution_report (
    id BIGSERIAL PRIMARY KEY,
    contribution_report_id BIGINT NOT NULL REFERENCES contribution_report (id),
    menu_add_report_id BIGINT REFERENCES menu_add_report (id),
    store_report_id BIGINT REFERENCES store_report (id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_menu_contribution_report_contribution_report UNIQUE (contribution_report_id),
    CONSTRAINT uq_menu_contribution_report_menu_add_report UNIQUE (menu_add_report_id),
    CONSTRAINT uq_menu_contribution_report_store_report UNIQUE (store_report_id),
    CONSTRAINT chk_menu_contribution_report_one_legacy_ref CHECK (
        (menu_add_report_id IS NOT NULL AND store_report_id IS NULL)
        OR (menu_add_report_id IS NULL AND store_report_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_menu_contribution_report_menu_add_report_id
    ON menu_contribution_report (menu_add_report_id)
    WHERE menu_add_report_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_menu_contribution_report_store_report_id
    ON menu_contribution_report (store_report_id)
    WHERE store_report_id IS NOT NULL;
