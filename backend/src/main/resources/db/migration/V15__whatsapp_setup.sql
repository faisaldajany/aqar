-- The step-by-step WhatsApp setup on the WhatsApp page, for offices without a WhatsApp Business account yet: what the
-- owner has confirmed so far, so they can continue from any device and the team (/ops) can see where they stopped.
-- steps: {"number": "2026-11-03T12:00:00Z", ...}, the time each step was confirmed. Steps Hidaa checks itself
-- (7 days of use, the connection, the first customer message) are not stored here.
create table whatsapp_setup (
    office_id           uuid primary key references offices (id),
    has_business_app    boolean,
    office_phone        text,
    business_app_since  date,
    steps               jsonb       not null default '{}',
    updated_at          timestamptz not null default now()
);

alter table whatsapp_setup enable row level security;
alter table whatsapp_setup force row level security;
create policy tenant_isolation on whatsapp_setup
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, update on whatsapp_setup to waslah_app;
