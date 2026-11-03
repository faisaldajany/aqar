-- Who had each handoff and for how long: a marketer gets it, and if nobody from the office answers within the
-- office's time limit it moves to the next marketer. These rows are also what marketer performance is counted from.
create table handoff_assignments (
    id            uuid primary key default gen_random_uuid(),
    office_id     uuid        not null references offices (id),
    handoff_id    uuid        not null references handoffs (id) on delete cascade,
    marketer_id   uuid        not null references marketers (id),
    assigned_at   timestamptz not null default now(),
    ended_at      timestamptz,
    ended_reason  text        check (ended_reason in ('TIMEOUT', 'MANUAL'))
);
create index handoff_assignments_handoff_idx on handoff_assignments (handoff_id, assigned_at desc);
create index handoff_assignments_marketer_idx on handoff_assignments (office_id, marketer_id, assigned_at desc);
alter table handoff_assignments enable row level security;
alter table handoff_assignments force row level security;
create policy tenant_isolation on handoff_assignments
    using (office_id = app_current_office()) with check (office_id = app_current_office());
grant select, insert, update, delete on handoff_assignments to waslah_app;

alter table handoffs
    add column assigned_at timestamptz,   -- when the current marketer got it
    add column claimed_at  timestamptz,
    add column closed_at   timestamptz;

-- Move an unanswered handoff to the next marketer after this many minutes (08:00–23:00 Riyadh); null = never.
alter table offices add column handoff_timeout_minutes int
    check (handoff_timeout_minutes is null or handoff_timeout_minutes between 5 and 240);
