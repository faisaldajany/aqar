-- Phase 4: viewing appointments (with a marketer, or a self-visit without one).

-- One row per office: when viewings can be booked and how confirmations are worded.
create table booking_settings (
    office_id                   uuid primary key references offices (id),
    days                        int[]   not null default '{0,1,2,3,4,6}',  -- 0 = Sunday … 6 = Saturday
    start_time                  time    not null default '16:00',
    end_time                    time    not null default '22:00',
    slot_minutes                int     not null default 45 check (slot_minutes between 15 and 240),
    buffer_minutes              int     not null default 15 check (buffer_minutes between 0 and 120),
    min_lead_minutes            int     not null default 60,
    reminder_minutes            int     not null default 60,
    self_visit_enabled          boolean not null default true,
    self_visit_requires_approval boolean not null default true,
    self_start_time             time    not null default '08:00',
    self_end_time               time    not null default '18:00',
    self_vacant_only            boolean not null default true,
    self_qualified_only         boolean not null default true,
    code_window_minutes         int     not null default 30,
    follow_up_enabled           boolean not null default true,
    template_rep                text    not null default
        'هلا {العميل}، تم تأكيد معاينتك لـ{العقار} في {الحي} يوم {اليوم} الساعة {الوقت} مع {المسوق} ({جوال_المسوق}).' || chr(10) ||
        'الموقع: {الموقع}' || chr(10) || 'لو احتجت تغيّر الموعد رد على هذه الرسالة.',
    template_self               text    not null default
        'هلا {العميل}، تم تأكيد زيارتك الذاتية لـ{العقار} في {الحي} يوم {اليوم} من {من} إلى {إلى}.' || chr(10) ||
        'الموقع: {الموقع}' || chr(10) || '{طريقة_الدخول}' || chr(10) ||
        'نرجو إغلاق الأبواب والأنوار بعد الزيارة. لأي سؤال رد على هذه الرسالة.',
    updated_at                  timestamptz not null default now()
);

-- How a customer gets in without a marketer, per listing.
alter table listings
    add column self_visit_access text[] not null default '{}',   -- LOCK, GUARD, KEY
    add column access_lock_note   text,
    add column access_guard       text,
    add column access_key_point   text;

create table bookings (
    id               uuid primary key default gen_random_uuid(),
    office_id        uuid        not null references offices (id),
    conversation_id  uuid        not null references conversations (id),
    listing_id       uuid        not null references listings (id),
    marketer_id      uuid        references marketers (id),
    mode             text        not null check (mode in ('WITH_MARKETER', 'SELF_VISIT')),
    starts_at        timestamptz not null,
    ends_at          timestamptz not null,
    status           text        not null check (status in ('PENDING_APPROVAL', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'DONE', 'NO_SHOW')),
    access_method    text        check (access_method in ('LOCK', 'GUARD', 'KEY')),
    access_code      text,
    customer_name    text,
    note             text,
    confirmation     text,
    reminder_sent_at timestamptz,
    follow_up_at     timestamptz,
    decided_at       timestamptz,
    created_at       timestamptz not null default now()
);
create index bookings_office_time_idx on bookings (office_id, starts_at);
create index bookings_marketer_time_idx on bookings (marketer_id, starts_at) where status in ('PENDING_APPROVAL', 'CONFIRMED');
create index bookings_listing_time_idx on bookings (listing_id, starts_at) where status in ('PENDING_APPROVAL', 'CONFIRMED');

do $$
declare t text;
begin
    foreach t in array array['booking_settings', 'bookings'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (office_id = app_current_office()) with check (office_id = app_current_office())', t);
        execute format('grant select, insert, update, delete on %I to waslah_app', t);
    end loop;
end $$;
