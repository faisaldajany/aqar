-- Phase 2: what the agent reads (listings, marketers) and writes (leads, handoffs, run log).

-- Loose Arabic matching for districts and property types:
-- "حي النرجس" = "النرجس" = "نرجس", and أ/إ/آ→ا, ة→ه, ى→ي.
create or replace function ar_norm(s text) returns text
    language sql immutable
as $$
    select regexp_replace(
               regexp_replace(translate(lower(trim(coalesce(s, ''))), 'أإآةى', 'اااهي'), '^حي\s+', ''),
               '^ال', '')
$$;

create table marketers (
    id          uuid primary key default gen_random_uuid(),
    office_id   uuid        not null references offices (id),
    name        text        not null,
    wa_phone    text        not null,
    districts   text[]      not null default '{}',
    active      boolean     not null default true,
    created_at  timestamptz not null default now()
);
create index marketers_office_idx on marketers (office_id);

create table listings (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid          not null references offices (id),
    title            text          not null,
    district         text          not null,
    property_type    text          not null,
    purpose          text          not null check (purpose in ('SALE', 'RENT')),
    price            numeric(14,2) not null check (price > 0),
    area_sqm         numeric(10,2),
    rooms            int,
    ad_license       text          not null,
    license_expiry   date          not null,
    vacant           boolean       not null default true,
    status           text          not null default 'ACTIVE' check (status in ('ACTIVE', 'HIDDEN', 'SOLD')),
    marketer_id      uuid          references marketers (id),
    aqar_url         text,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now()
);
create index listings_search_idx on listings (office_id, status, purpose, district);

-- What the agent learned about the customer, one row per conversation.
create table leads (
    conversation_id  uuid primary key references conversations (id),
    office_id        uuid        not null references offices (id),
    customer_name    text,
    purpose          text,
    property_type    text,
    districts        text,
    budget_min       numeric(14,2),
    budget_max       numeric(14,2),
    payment          text,
    timeline         text,
    notes            text,
    updated_at       timestamptz not null default now()
);

create table handoffs (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    conversation_id  uuid        not null references conversations (id),
    marketer_id      uuid        references marketers (id),
    reason           text        not null,
    summary          text        not null,
    listing_id       uuid        references listings (id),
    status           text        not null default 'OPEN' check (status in ('OPEN', 'CLAIMED', 'CLOSED')),
    created_at       timestamptz not null default now()
);
create index handoffs_open_idx on handoffs (office_id, status, created_at desc);

-- One row per agent turn: cost, latency and tool use, for the office report and our own monitoring.
create table agent_runs (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    conversation_id  uuid        not null references conversations (id),
    trigger_message  uuid,
    model            text        not null,
    input_tokens     int         not null default 0,
    output_tokens    int         not null default 0,
    latency_ms       int         not null default 0,
    tool_calls       jsonb       not null default '[]',
    outcome          text        not null,
    error            text,
    created_at       timestamptz not null default now()
);
create index agent_runs_office_idx on agent_runs (office_id, created_at desc);

do $$
declare t text;
begin
    foreach t in array array['marketers', 'listings', 'leads', 'handoffs', 'agent_runs'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (office_id = app_current_office()) with check (office_id = app_current_office())', t);
        execute format('grant select, insert, update, delete on %I to waslah_app', t);
    end loop;
end $$;
