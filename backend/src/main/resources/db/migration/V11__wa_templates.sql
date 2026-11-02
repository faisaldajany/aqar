-- WhatsApp message templates on each office's own WhatsApp Business account. With coexistence every office brings
-- its own account (WABA), and a template approved on one account cannot be sent from another, so Hidaa creates the
-- four it needs (handoff alert, viewing reminder, post-visit follow-up, daily report) on each office's account and
-- tracks Meta's review here.
create table wa_templates (
    id             uuid primary key default gen_random_uuid(),
    office_id      uuid        not null references offices (id),
    wa_account_id  uuid        not null references wa_accounts (id),
    kind           text        not null check (kind in ('HANDOFF', 'REMINDER', 'FOLLOWUP', 'REPORT')),
    name           text        not null,
    language       text        not null default 'ar',
    status         text        not null default 'PENDING',  -- Meta's status (APPROVED, PENDING, REJECTED, PAUSED, DISABLED…) or ERROR
    meta_id        text,
    reason         text,
    checked_at     timestamptz,
    updated_at     timestamptz not null default now(),
    unique (wa_account_id, kind)
);
create index wa_templates_office_idx on wa_templates (office_id);

alter table wa_templates enable row level security;
alter table wa_templates force row level security;
create policy tenant_isolation on wa_templates
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, update, delete on wa_templates to waslah_app;
