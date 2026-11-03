-- Telling past customers about a new listing that fits what they asked for (one message per customer per listing),
-- and the automatic check-in with serious customers who went quiet. Customers who answer «إيقاف» get neither.
create table listing_alerts (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    listing_id       uuid        not null references listings (id) on delete cascade,
    conversation_id  uuid        not null references conversations (id),
    status           text        not null check (status in ('SENT', 'FAILED')),
    via              text        check (via in ('TEXT', 'TEMPLATE')),
    error            text,
    created_at       timestamptz not null default now(),
    unique (listing_id, conversation_id)
);
create index listing_alerts_conv_idx on listing_alerts (conversation_id, created_at desc);
alter table listing_alerts enable row level security;
alter table listing_alerts force row level security;
create policy tenant_isolation on listing_alerts
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, update, delete on listing_alerts to waslah_app;

alter table contacts add column marketing_opt_out_at timestamptz;
-- The last automatic check-in; one per quiet spell (a new customer message starts a new one).
alter table conversations add column checked_in_at timestamptz;
-- Check in with serious customers this many days after they stopped replying; null = off.
alter table offices add column quiet_followup_days int check (quiet_followup_days is null or quiet_followup_days between 2 and 30);

-- The two marketing templates behind these messages, created on each office's WhatsApp account like the others.
alter table wa_templates drop constraint if exists wa_templates_kind_check;
alter table wa_templates add constraint wa_templates_kind_check
    check (kind in ('HANDOFF', 'REMINDER', 'FOLLOWUP', 'REPORT', 'NEW_LISTING', 'CHECKIN'));
