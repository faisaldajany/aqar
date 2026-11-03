-- Quality reviews of the agent's replies, made from the operator console (/ops → مراجعة الردود): an AI reviewer reads a
-- conversation (customer details masked) with the listings the agent's tools returned, and records a score and the
-- problems it found. Tenant data; deleted with the conversation's messages (privacy erasure, retention).
create table agent_reviews (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    conversation_id  uuid        not null references conversations (id),
    messages         int         not null,   -- how many messages the conversation had when reviewed
    score            int         not null check (score between 1 and 5),
    verdict          text        not null check (verdict in ('OK', 'ISSUE')),
    summary          text,
    issues           jsonb       not null default '[]',
    model            text        not null,
    created_at       timestamptz not null default now()
);
create index agent_reviews_conv_idx on agent_reviews (conversation_id, created_at desc);
create index agent_reviews_office_idx on agent_reviews (office_id, created_at desc);

alter table agent_reviews enable row level security;
alter table agent_reviews force row level security;
create policy tenant_isolation on agent_reviews
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, delete on agent_reviews to waslah_app;
