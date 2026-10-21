-- Soft-deleted course stores must not reserve an order or prevent the same store
-- from being added again. Reordering temporarily uses negative orders and flushes
-- before assigning the final positive orders.
ALTER TABLE course_stores
    DROP CONSTRAINT IF EXISTS uq_course_store_order;

ALTER TABLE course_stores
    DROP CONSTRAINT IF EXISTS uq_course_store_store;

CREATE UNIQUE INDEX IF NOT EXISTS uq_course_store_order
    ON course_stores (course_id, visit_order)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_course_store_store
    ON course_stores (course_id, store_id)
    WHERE deleted_at IS NULL;
