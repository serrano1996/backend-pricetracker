-- Flyway callback, runs after every migrate (idempotent).
-- The history table lives in public, which Supabase exposes through its Data API; tampering with it
-- would break startup validation. Done here, not in a versioned migration: inside a migration this
-- ALTER waits forever on the lock Flyway holds on its own history table.
alter table flyway_schema_history enable row level security;
