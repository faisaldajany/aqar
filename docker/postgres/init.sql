-- Runs once when the local Postgres container is first created.
-- waslah      = schema owner, used only by Flyway migrations.
-- waslah_app  = runtime role. Not a superuser and not the table owner, so row-level security applies.
create role waslah_app login password 'waslah_app' nosuperuser nobypassrls;
