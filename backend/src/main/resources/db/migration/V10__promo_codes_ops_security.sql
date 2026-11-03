-- Promo codes, the operator console (office suspension, disabled users, audit log) and password reset.

-- Promo codes are platform data, not one office's: read at signup and checkout, created only by the operator.
create table promo_codes (
    id                  uuid primary key default gen_random_uuid(),
    code                text        not null unique check (code ~ '^[A-Z0-9_-]{3,32}$'),
    kind                text        not null check (kind in ('PERCENT', 'FIXED', 'TRIAL_DAYS')),
    percent_off         int         check (percent_off between 1 and 100),
    amount_off_halalas  bigint      check (amount_off_halalas > 0),
    trial_days          int         check (trial_days between 1 and 180),
    -- Number of paid subscription invoices the discount applies to; null = every renewal.
    duration_periods    int         check (duration_periods between 1 and 36),
    plans               text[]      not null default '{}',   -- empty = all plans
    cycles              text[]      not null default '{}',   -- empty = monthly and annual
    new_customers_only  boolean     not null default true,
    max_redemptions     int         check (max_redemptions > 0),
    redemptions         int         not null default 0,
    starts_at           timestamptz,
    expires_at          timestamptz,
    active              boolean     not null default true,
    note                text,
    created_at          timestamptz not null default now(),
    constraint promo_value check (
        (kind = 'PERCENT' and percent_off is not null) or
        (kind = 'FIXED' and amount_off_halalas is not null) or
        (kind = 'TRIAL_DAYS' and trial_days is not null))
);
grant select, insert, update on promo_codes to waslah_app;

-- Codes an office has claimed: once per office per code. A row is the office's use of the code's limit, taken when the
-- code is applied (so a capped code cannot go over its cap) and given back if an unused discount is removed. Tenant data.
create table promo_redemptions (
    office_id      uuid        not null references offices (id),
    promo_code_id  uuid        not null references promo_codes (id),
    created_at     timestamptz not null default now(),
    primary key (office_id, promo_code_id)
);
alter table promo_redemptions enable row level security;
alter table promo_redemptions force row level security;
create policy tenant_isolation on promo_redemptions
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, delete on promo_redemptions to waslah_app;

alter table offices
    add column promo_code_id       uuid references promo_codes (id),
    add column promo_periods_used  int not null default 0,
    add column suspended_at        timestamptz,
    add column suspended_reason    text;

alter table invoices
    add column discount_halalas  bigint not null default 0,
    add column promo_code_id     uuid references promo_codes (id),
    add column promo_code        text;
alter table invoices drop constraint if exists invoices_kind_check;
alter table invoices add constraint invoices_kind_check check (kind in ('SUBSCRIPTION', 'OVERAGE', 'SETUP', 'COMP'));

-- Sign-in controls: a disabled user cannot sign in, and sessions issued before a password change stop working.
alter table users
    add column disabled_at          timestamptz,
    add column password_changed_at  timestamptz,
    add column last_login_at        timestamptz;

-- One-time password reset links. Only a SHA-256 of the token is stored.
create table password_resets (
    token_hash  text primary key,
    user_id     uuid        not null references users (id),
    created_by  text        not null default 'USER' check (created_by in ('USER', 'OPERATOR')),
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    used_at     timestamptz
);
create index password_resets_user_idx on password_resets (user_id);
grant select, insert, update on password_resets to waslah_app;

-- Every operator action, for accountability.
create table ops_audit (
    id         bigserial primary key,
    at         timestamptz not null default now(),
    action     text        not null,
    office_id  uuid,
    target     text,
    detail     text,
    ip_hash    text
);
create index ops_audit_at_idx on ops_audit (at desc);
grant select, insert on ops_audit to waslah_app;
grant usage on sequence ops_audit_id_seq to waslah_app;
