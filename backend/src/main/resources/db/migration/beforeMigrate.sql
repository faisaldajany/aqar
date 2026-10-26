-- Runs before every Flyway migrate. On a managed database (Cloud SQL) there is no init script, so the
-- non-superuser runtime role is created here the first time. Local Docker and tests already have it.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'waslah_app') then
        create role waslah_app login password '${app_password}' nosuperuser nobypassrls;
    end if;
end $$;
