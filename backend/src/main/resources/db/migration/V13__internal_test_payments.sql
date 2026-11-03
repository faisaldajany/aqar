-- Internal (Hidaa team) offices pay through Moyasar test mode. An office is internal when its owner's email is on the
-- company domain (hidaaco.com) and confirmed by a link sent to that inbox, so nobody else can claim it by typing one.
alter table users add column email_confirmed_at timestamptz;

-- One-time email confirmation links; only a SHA-256 of the token is stored.
create table email_confirmations (
    token_hash  text primary key,
    user_id     uuid        not null references users (id),
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    used_at     timestamptz
);
create index email_confirmations_user_idx on email_confirmations (user_id);
grant select, insert, update on email_confirmations to waslah_app;
