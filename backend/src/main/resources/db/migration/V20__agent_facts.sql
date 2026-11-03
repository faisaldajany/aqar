-- What the office taught the agent: questions customers ask that the listings do not answer (working hours,
-- commission policy, how viewings work, a building's amenities), with the office's own answer. Sent to the agent
-- with every conversation.
create table agent_facts (
    id          uuid primary key default gen_random_uuid(),
    office_id   uuid        not null references offices (id),
    question    text        not null check (length(question) between 2 and 300),
    answer      text        not null check (length(answer) between 1 and 600),
    created_at  timestamptz not null default now()
);
create index agent_facts_office_idx on agent_facts (office_id, created_at);
alter table agent_facts enable row level security;
alter table agent_facts force row level security;
create policy tenant_isolation on agent_facts
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, update, delete on agent_facts to waslah_app;
