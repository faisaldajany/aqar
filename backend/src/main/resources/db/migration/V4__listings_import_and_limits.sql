-- Phase 3: listing cards, bulk import, license alerts, plans and usage limits.

-- Plan decides reply allowance and marketer seats (see PlanLimits).
alter table offices add column plan text not null default 'BASIC' check (plan in ('BASIC', 'GROWTH'));

-- Listing card: map pin, video/tour link, external photo URLs (uploaded photos live in listing_photos).
alter table listings
    add column latitude      double precision,
    add column longitude     double precision,
    add column video_url     text,
    add column photo_urls    text[] not null default '{}',
    add column description   text,
    add column source        text   not null default 'MANUAL' check (source in ('MANUAL', 'FILE', 'AQAR_LINK', 'AQAR_FEED')),
    add column source_url    text;

-- An ad license identifies one listing per office: re-imports update instead of duplicating.
create unique index listings_office_license_uq on listings (office_id, ad_license);

create table listing_photos (
    id            uuid primary key default gen_random_uuid(),
    office_id     uuid        not null references offices (id),
    listing_id    uuid        not null references listings (id) on delete cascade,
    content_type  text        not null check (content_type in ('image/jpeg', 'image/png', 'image/webp')),
    bytes         bytea       not null,
    position      int         not null default 0,
    created_at    timestamptz not null default now()
);
create index listing_photos_listing_idx on listing_photos (listing_id, position);

-- An uploaded spreadsheet, parsed and held until the office confirms the column mapping.
create table import_batches (
    id           uuid primary key default gen_random_uuid(),
    office_id    uuid        not null references offices (id),
    file_name    text        not null,
    headers      jsonb       not null,
    rows         jsonb       not null,
    status       text        not null default 'PENDING' check (status in ('PENDING', 'COMMITTED', 'DISCARDED')),
    created      int,
    updated      int,
    skipped      int,
    created_at   timestamptz not null default now(),
    committed_at timestamptz
);

-- Things the office should act on: licenses about to expire, usage near the plan limit, etc.
create table office_alerts (
    id          uuid primary key default gen_random_uuid(),
    office_id   uuid        not null references offices (id),
    kind        text        not null,
    subject_id  uuid,
    message     text        not null,
    dedupe_key  text        not null,
    read_at     timestamptz,
    created_at  timestamptz not null default now(),
    unique (office_id, dedupe_key)
);
create index office_alerts_open_idx on office_alerts (office_id, read_at, created_at desc);

-- Offices that asked for an official Aqar feed.
create table aqar_feed_requests (
    office_id    uuid primary key references offices (id),
    aqar_profile text,
    created_at   timestamptz not null default now()
);

do $$
declare t text;
begin
    foreach t in array array['listing_photos', 'import_batches', 'office_alerts', 'aqar_feed_requests'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy tenant_isolation on %I using (office_id = app_current_office()) with check (office_id = app_current_office())', t);
        execute format('grant select, insert, update, delete on %I to waslah_app', t);
    end loop;
end $$;
