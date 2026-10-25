-- Phase 7 (PDPL): raw webhook payloads are purged after a short window; customers can be erased on request.
grant select, delete on webhook_events to waslah_app;
alter table contacts add column erased_at timestamptz;
