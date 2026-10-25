-- Row-level security. The app connects as waslah_app (created outside migrations, see docker/postgres/init.sql),
-- which is NOT a superuser and does NOT own these tables, so these policies always apply to it.
-- The tenant is carried per connection in the setting app.office_id (set by TenantDataSource).

create or replace function app_current_office() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.office_id', true), '')::uuid $$;

alter table offices enable row level security;
alter table offices force row level security;
create policy tenant_isolation on offices
    using (id = app_current_office())
    with check (id = app_current_office());

alter table wa_accounts enable row level security;
alter table wa_accounts force row level security;
create policy tenant_isolation on wa_accounts
    using (office_id = app_current_office())
    with check (office_id = app_current_office());

alter table contacts enable row level security;
alter table contacts force row level security;
create policy tenant_isolation on contacts
    using (office_id = app_current_office())
    with check (office_id = app_current_office());

alter table conversations enable row level security;
alter table conversations force row level security;
create policy tenant_isolation on conversations
    using (office_id = app_current_office())
    with check (office_id = app_current_office());

alter table messages enable row level security;
alter table messages force row level security;
create policy tenant_isolation on messages
    using (office_id = app_current_office())
    with check (office_id = app_current_office());

grant select, insert, update, delete on offices, wa_accounts, contacts, conversations, messages to waslah_app;
grant select, insert, update on users to waslah_app;
grant select, insert on wa_phone_routes to waslah_app;
grant insert on webhook_events to waslah_app;
grant usage on sequence webhook_events_id_seq to waslah_app;
