-- Phase 6: self-serve subscription (14-day trial, Moyasar invoices), daily manager report, FAL review.

alter table offices
    add column subscription_status text        not null default 'TRIAL'
        check (subscription_status in ('TRIAL', 'ACTIVE', 'PAST_DUE', 'EXPIRED', 'CANCELLED')),
    add column trial_ends_at       timestamptz not null default now() + interval '14 days',
    add column billing_cycle       text        not null default 'MONTHLY' check (billing_cycle in ('MONTHLY', 'ANNUAL')),
    add column paid_until          timestamptz,
    add column fal_status          text        not null default 'PENDING_REVIEW'
        check (fal_status in ('PENDING_REVIEW', 'VERIFIED', 'REJECTED')),
    add column fal_note            text,
    add column owner_phone         text,
    add column report_enabled      boolean     not null default true,
    add column report_hour         int         not null default 21 check (report_hour between 0 and 23);

alter table booking_settings add column reviewed_at timestamptz;

create table invoices (
    id                uuid primary key default gen_random_uuid(),
    office_id         uuid        not null references offices (id),
    number            text        not null unique,
    kind              text        not null check (kind in ('SUBSCRIPTION', 'OVERAGE', 'SETUP')),
    plan              text,
    cycle             text,
    description       text        not null,
    period_start      date,
    period_end        date,
    subtotal_halalas  bigint      not null,
    vat_halalas       bigint      not null,
    total_halalas     bigint      not null,
    status            text        not null default 'DUE' check (status in ('DUE', 'PAID', 'VOID')),
    provider          text,
    provider_ref      text,
    pay_url           text,
    created_at        timestamptz not null default now(),
    paid_at           timestamptz
);
create index invoices_office_idx on invoices (office_id, created_at desc);
-- One overage invoice per office per month, however often the job runs.
create unique index invoices_overage_once on invoices (office_id, period_start) where kind = 'OVERAGE';

-- Payment-provider references arrive on a webhook before any tenant is known (like wa_phone_routes).
create table billing_refs (
    provider_ref  text primary key,
    office_id     uuid not null references offices (id),
    invoice_id    uuid not null
);

create table daily_reports (
    office_id    uuid        not null references offices (id),
    report_date  date        not null,
    body         text        not null,
    sent_to      text,
    sent_at      timestamptz,
    error        text,
    primary key (office_id, report_date)
);

do $$
declare t text;
begin
    foreach t in array array['invoices', 'daily_reports'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (office_id = app_current_office()) with check (office_id = app_current_office())', t);
        execute format('grant select, insert, update, delete on %I to waslah_app', t);
    end loop;
end $$;
grant select, insert on billing_refs to waslah_app;
