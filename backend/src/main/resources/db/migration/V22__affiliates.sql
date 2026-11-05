-- Hidaa's marketers (affiliates): people outside Hidaa who bring offices with their own promo code and earn a share of
-- what those offices pay. Platform data, not one office's, like promo_codes: written by the operator and by billing.

create table affiliates (
    id                   uuid primary key default gen_random_uuid(),
    full_name            text          not null,
    email                text          not null unique,      -- lowercase; their sign-in
    phone                text,
    commission_percent   numeric(5, 2) not null check (commission_percent > 0 and commission_percent <= 100),
    iban                 text,                               -- where payouts go; the operator's reference only
    note                 text,
    active               boolean       not null default true,
    password_hash        text,                               -- null until they set one from the invitation
    password_changed_at  timestamptz,
    last_login_at        timestamptz,
    created_at           timestamptz   not null default now()
);
grant select, insert, update on affiliates to waslah_app;

-- A code belongs to at most one marketer; set when the code is created.
alter table promo_codes add column affiliate_id uuid references affiliates (id);
create index promo_codes_affiliate_idx on promo_codes (affiliate_id) where affiliate_id is not null;

-- Which marketer an office came with: the last code the office applied, when that code is a marketer's. Removed when
-- the office applies another code or removes this one. A routing table (no RLS) so a marketer's page can find its
-- offices; what it may show is still read under each office's tenant.
create table affiliate_clients (
    office_id      uuid        primary key references offices (id),
    affiliate_id   uuid        not null references affiliates (id),
    promo_code_id  uuid        not null references promo_codes (id),
    since          timestamptz not null default now()
);
create index affiliate_clients_affiliate_idx on affiliate_clients (affiliate_id);
grant select, insert, update, delete on affiliate_clients to waslah_app;

-- Payouts the team made to a marketer (a bank transfer), each settling every pending commission at the time.
create table affiliate_payouts (
    id              uuid primary key default gen_random_uuid(),
    affiliate_id    uuid        not null references affiliates (id),
    amount_halalas  bigint      not null check (amount_halalas > 0),
    reference       text,
    note            text,
    created_at      timestamptz not null default now()
);
create index affiliate_payouts_affiliate_idx on affiliate_payouts (affiliate_id, created_at desc);
grant select, insert on affiliate_payouts to waslah_app;

-- The commission ledger. EARNED: a paid invoice of a marketer's client (at most one per invoice). REFUND: a negative
-- correction when that payment is refunded. Pending until a payout settles it.
create table affiliate_commissions (
    id              uuid primary key default gen_random_uuid(),
    affiliate_id    uuid          not null references affiliates (id),
    office_id       uuid          not null references offices (id),
    invoice_id      uuid          not null,
    promo_code_id   uuid          references promo_codes (id),
    entry           text          not null check (entry in ('EARNED', 'REFUND')),
    invoice_kind    text          not null,
    base_halalas    bigint        not null,   -- what the office paid before VAT (negative on a refund)
    percent         numeric(5, 2) not null,
    amount_halalas  bigint        not null,   -- the marketer's share (negative on a refund)
    payout_id       uuid          references affiliate_payouts (id),
    created_at      timestamptz   not null default now()
);
create unique index affiliate_commissions_earned_once on affiliate_commissions (invoice_id) where entry = 'EARNED';
create index affiliate_commissions_affiliate_idx on affiliate_commissions (affiliate_id, created_at desc);
grant select, insert, update on affiliate_commissions to waslah_app;

-- One-time links to set a password (the invitation, or a forgotten password). Only a SHA-256 of the token is stored.
create table affiliate_tokens (
    token_hash    text primary key,
    affiliate_id  uuid        not null references affiliates (id),
    created_at    timestamptz not null default now(),
    expires_at    timestamptz not null,
    used_at       timestamptz
);
create index affiliate_tokens_affiliate_idx on affiliate_tokens (affiliate_id);
grant select, insert, update on affiliate_tokens to waslah_app;
