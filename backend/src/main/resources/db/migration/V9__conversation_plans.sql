-- Plans priced per conversation: STARTER 199 / BASIC 499 / GROWTH 999.
alter table offices drop constraint if exists offices_plan_check;
alter table offices add constraint offices_plan_check check (plan in ('STARTER', 'BASIC', 'GROWTH'));

-- A billable conversation: one customer chat the agent answered, counted once per 24 hours
-- however many messages are exchanged in that window.
create table agent_sessions (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    conversation_id  uuid        not null references conversations (id),
    started_at       timestamptz not null default now()
);
create index agent_sessions_office_idx on agent_sessions (office_id, started_at);
create index agent_sessions_conv_idx on agent_sessions (conversation_id, started_at desc);

alter table agent_sessions enable row level security;
alter table agent_sessions force row level security;
create policy tenant_isolation on agent_sessions using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, update, delete on agent_sessions to waslah_app;
