-- Document imports update existing rows; record that separately from skipped duplicates.
alter table import_batch add column rows_updated integer not null default 0;
