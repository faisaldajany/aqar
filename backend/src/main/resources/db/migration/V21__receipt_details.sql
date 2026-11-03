-- For the payment receipt (email and printable page): how it was paid, from Moyasar's own record of the payment.
alter table invoices
    add column paid_method     text,   -- mada, visa, master, applepay… or MANUAL (bank transfer recorded by the team)
    add column paid_last4      text,
    add column paid_reference  text;   -- the bank's reference number, or the transfer reference
-- When the office was emailed that this invoice is due (renewals are offered daily; the email goes once).
alter table invoices add column due_emailed_at timestamptz;
