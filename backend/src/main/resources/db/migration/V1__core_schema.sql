-- Core schema. Every tenant-owned table carries office_id; isolation is enforced by RLS in V2.

create table offices (
    id           uuid primary key,
    name         text        not null,
    fal_license  text        not null,
    status       text        not null default 'onboarding',
    created_at   timestamptz not null default now()
);

-- Identity table: looked up by email at login before any tenant is known, so it is not under RLS.
-- The application never lists users without an office_id filter.
create table users (
    id             uuid primary key,
    office_id      uuid        not null references offices (id),
    email          text        not null unique,
    password_hash  text        not null,
    full_name      text        not null,
    role           text        not null check (role in ('OWNER', 'MANAGER', 'MARKETER')),
    created_at     timestamptz not null default now()
);
create index users_office_idx on users (office_id);

create table wa_accounts (
    id                uuid primary key default gen_random_uuid(),
    office_id         uuid        not null references offices (id),
    waba_id           text        not null,
    phone_number_id   text        not null unique,
    display_phone     text,
    verified_name     text,
    access_token_enc  text        not null,
    coexistence       boolean     not null default true,
    status            text        not null default 'connected',
    connected_at      timestamptz not null default now()
);
create index wa_accounts_office_idx on wa_accounts (office_id);

-- Webhook routing: maps Meta's phone_number_id to the owning office before a tenant is known.
-- Holds no customer data, so it is outside RLS.
create table wa_phone_routes (
    phone_number_id  text primary key,
    office_id        uuid not null references offices (id)
);

create table contacts (
    id            uuid primary key default gen_random_uuid(),
    office_id     uuid        not null references offices (id),
    wa_id         text        not null,
    profile_name  text,
    created_at    timestamptz not null default now(),
    unique (office_id, wa_id)
);

create table conversations (
    id                    uuid primary key,
    office_id             uuid        not null references offices (id),
    contact_id            uuid        not null references contacts (id),
    wa_account_id         uuid        references wa_accounts (id),
    last_inbound_at       timestamptz,
    last_message_at       timestamptz,
    agent_paused_until    timestamptz,
    assigned_marketer_id  uuid,
    created_at            timestamptz not null default now(),
    unique (office_id, contact_id)
);
create index conversations_recent_idx on conversations (office_id, last_message_at desc);

create table messages (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    conversation_id  uuid        not null references conversations (id),
    wa_message_id    text,
    direction        text        not null check (direction in ('IN', 'OUT')),
    sender           text        not null check (sender in ('CUSTOMER', 'AGENT', 'HUMAN', 'SYSTEM')),
    type             text        not null,
    body             text,
    media_id         text,
    status           text,
    error            text,
    created_at       timestamptz not null default now(),
    unique (office_id, wa_message_id)
);
create index messages_conversation_idx on messages (conversation_id, created_at);

-- Raw webhook log for debugging and replay. Insert-only for the app role.
create table webhook_events (
    id           bigserial primary key,
    office_id    uuid,
    field        text,
    payload      jsonb       not null,
    received_at  timestamptz not null default now()
);
