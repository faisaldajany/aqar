-- Public support form. Not tenant data (anyone can write, only the operator reads), so no RLS.
create table support_messages (
    id             uuid primary key default gen_random_uuid(),
    office_id      uuid references offices (id),
    name           text        not null,
    email          text,
    phone          text,
    topic          text        not null,
    message        text        not null,
    source         text        not null default 'portal',
    ip_hash        text,
    created_at     timestamptz not null default now(),
    forwarded_at   timestamptz,
    forward_error  text
);
create index support_messages_ip_idx on support_messages (ip_hash, created_at);
grant select, insert, update on support_messages to waslah_app;
