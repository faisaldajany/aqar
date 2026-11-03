-- Moyasar events beyond "paid": refunds (REFUNDED, or a partial refund on a paid invoice), the last failed payment
-- attempt and its reason, and when the hosted payment page stops working (Moyasar invoices expire after 7 days).
alter table invoices drop constraint if exists invoices_status_check;
alter table invoices add constraint invoices_status_check check (status in ('DUE', 'PAID', 'VOID', 'REFUNDED'));

alter table invoices
    add column refunded_halalas     bigint not null default 0,
    add column refunded_at          timestamptz,
    add column last_failure_at      timestamptz,
    add column last_failure_reason  text,
    add column pay_url_expires_at   timestamptz;

update invoices set pay_url_expires_at = created_at + interval '7 days' where pay_url is not null;
