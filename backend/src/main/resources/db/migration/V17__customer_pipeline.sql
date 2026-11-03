-- Where each customer stands in the office's own pipeline (set from قائمة العملاء), and the deal it ended in.
-- Hidaa also moves a customer forward by itself: contacted once a person replied or a viewing was booked, viewed once a
-- viewing is done; that is computed when listing, so only what the office set by hand is stored here.
alter table leads
    add column stage        text not null default 'NEW' check (stage in ('NEW', 'CONTACTED', 'VIEWING', 'WON', 'LOST')),
    add column stage_at     timestamptz,
    add column deal_value   numeric(14,2) check (deal_value is null or deal_value >= 0),
    add column lost_reason  text;

create index leads_stage_idx on leads (office_id, stage, stage_at desc);
