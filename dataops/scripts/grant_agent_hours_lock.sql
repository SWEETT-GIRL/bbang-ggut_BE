-- Approved minimal permission for exact business-hours row locking.
-- Run against the serving database as its table owner/administrator.
-- No store data is modified. This also permits direct UPDATE of day_of_week.
GRANT UPDATE(day_of_week) ON public.store_business_hour TO dataops_writer;

SELECT has_column_privilege('dataops_writer', 'public.store_business_hour', 'day_of_week', 'UPDATE')
    AS hours_row_lock_permission;
