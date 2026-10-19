-- Spec 001 schema. Design rationale: specs/001-backend-search-mvp/plan.md §2 and docs/architecture.md §6.

create table products (
    id             uuid primary key default gen_random_uuid(),
    canonical_name text        not null,
    category       text,
    image_url      text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create table store_products (
    id                 uuid primary key default gen_random_uuid(),
    -- null until cross-store matching exists (future spec)
    product_id         uuid references products (id) on delete set null,
    store_name         text          not null check (store_name in ('mercadona', 'alcampo', 'dia')),
    warehouse          text          not null,
    external_id        text          not null,
    store_product_name text          not null,
    category           text,
    image_url          text,
    current_price      numeric(10,2) not null,
    price_format       text,
    unit_price         numeric(10,2),
    last_checked_at    timestamptz   not null,
    created_at         timestamptz   not null default now(),
    -- prices differ per warehouse, so external_id alone is not unique
    unique (store_name, warehouse, external_id)
);
create index store_products_product_id_idx on store_products (product_id);

create table price_history (
    id               bigint generated always as identity primary key,
    store_product_id uuid          not null references store_products (id) on delete cascade,
    price            numeric(10,2) not null,
    unit_price       numeric(10,2),
    recorded_at      timestamptz   not null
);
-- also serves the FK (leading column)
create index price_history_product_time_idx on price_history (store_product_id, recorded_at desc);

create table search_snapshots (
    id          uuid primary key default gen_random_uuid(),
    query_term  text        not null check (char_length(query_term) between 1 and 100),
    postal_code text        not null check (postal_code ~ '^[0-9]{5}$'),
    store_name  text        not null check (store_name in ('mercadona', 'alcampo', 'dia')),
    -- 'unavailable' is never stored: a failure must not overwrite the last good snapshot
    status      text        not null check (status in ('ok', 'not_served')),
    fetched_at  timestamptz not null,
    unique (query_term, postal_code, store_name)
);

create table search_snapshot_items (
    snapshot_id      uuid not null references search_snapshots (id) on delete cascade,
    position         int  not null check (position >= 0),
    store_product_id uuid not null references store_products (id) on delete cascade,
    primary key (snapshot_id, position)
);
create index search_snapshot_items_store_product_id_idx on search_snapshot_items (store_product_id);

create table search_metrics (
    id               bigint generated always as identity primary key,
    query_term       text        not null check (char_length(query_term) between 1 and 100),
    postal_code      text        not null check (postal_code ~ '^[0-9]{5}$'),
    search_count     bigint      not null default 0,
    last_searched_at timestamptz not null,
    unique (query_term, postal_code)
);
create index search_metrics_rank_idx on search_metrics (search_count desc, last_searched_at desc);

-- Supabase exposes the public schema through its Data API (anon key). RLS on with no policies
-- denies that path entirely; the backend connects as the table owner, which bypasses RLS.
alter table products              enable row level security;
alter table store_products        enable row level security;
alter table price_history         enable row level security;
alter table search_snapshots      enable row level security;
alter table search_snapshot_items enable row level security;
alter table search_metrics        enable row level security;
-- flyway_schema_history is protected in afterMigrate.sql: altering it here deadlocks with Flyway's own lock
