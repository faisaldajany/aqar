-- Every new account verifies its email: the email carries a link and a 6-digit code (either works). The code is short
-- lived and allows 5 wrong tries; only hashes are stored.
alter table email_confirmations
    add column code_hash        text,
    add column code_expires_at  timestamptz,
    add column attempts         int not null default 0;

-- Accounts made before verification existed count as verified, except company (hidaaco.com) addresses: those decide
-- test payments, so they still need the link.
update users set email_confirmed_at = created_at
where email_confirmed_at is null and lower(email) not like '%@hidaaco.com';
