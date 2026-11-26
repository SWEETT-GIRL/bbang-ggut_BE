-- Run in the soboro console as table owner/administrator. Does not recreate existing roles.
-- No data rows are changed. Passwords are configured outside this file.
DO $$
BEGIN
    IF current_database() <> 'soboro' THEN
        RAISE EXCEPTION 'Connect to soboro before granting serving permissions';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dataops_writer') THEN
        RAISE EXCEPTION 'Existing serving role dataops_writer is required';
    END IF;
END $$;
GRANT CONNECT ON DATABASE soboro TO dataops_writer;
GRANT USAGE ON SCHEMA public TO dataops_writer;
GRANT SELECT ON public.store, public.menu, public.menu_category,
    public.store_name_alias, public.store_business_hour TO dataops_writer;
GRANT UPDATE(name, phone, instagram_handle, address, location, region_1st, region_2nd, region_3rd, updated_at) ON public.store TO dataops_writer;
GRANT INSERT, UPDATE(price, description, menu_category_id, deleted_at, updated_at) ON public.menu TO dataops_writer;
GRANT INSERT ON public.store_name_alias TO dataops_writer;
GRANT INSERT, DELETE, UPDATE(day_of_week) ON public.store_business_hour TO dataops_writer;
-- Identity/serial sequence names are obtained from the actual columns, not guessed.
DO $$
DECLARE sequence_name text;
BEGIN
    FOR sequence_name IN
        SELECT pg_get_serial_sequence('public.' || name, 'id')
        FROM unnest(ARRAY['menu', 'store_name_alias', 'store_business_hour']) AS name
    LOOP
        IF sequence_name IS NOT NULL THEN
            EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO dataops_writer', sequence_name);
        END IF;
    END LOOP;
END $$;
