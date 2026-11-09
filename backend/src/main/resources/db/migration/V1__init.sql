-- Money is NUMERIC(16,2): exact decimal, never floating point.

create table company (
    id               bigserial primary key,
    name             varchar(200)   not null,
    currency         char(3)        not null default 'ILS',
    opening_balance  numeric(16, 2) not null,
    opening_date     date           not null,
    minimum_balance  numeric(16, 2) not null default 0,
    created_at       timestamptz    not null default now()
);

create table bank_transaction (
    id            bigserial primary key,
    company_id    bigint         not null references company (id) on delete cascade,
    booking_date  date           not null,
    amount        numeric(16, 2) not null check (amount <> 0),
    description   varchar(500)   not null,
    counterparty  varchar(200)   not null,
    -- sha256 of the row's content plus its occurrence number within the file, so re-importing the
    -- same export is a no-op while two genuinely identical rows in one file both count.
    fingerprint   char(64)       not null,
    unique (company_id, fingerprint)
);

create index bank_transaction_company_date on bank_transaction (company_id, booking_date);

create table document (
    id            bigserial primary key,
    company_id    bigint         not null references company (id) on delete cascade,
    type          varchar(8)     not null check (type in ('INVOICE', 'BILL')),
    number        varchar(100)   not null,
    counterparty  varchar(200)   not null,
    issue_date    date           not null,
    due_date      date           not null,
    amount        numeric(16, 2) not null check (amount > 0),
    paid_date     date,
    unique (company_id, type, number),
    check (due_date >= issue_date)
);

create index document_company_type on document (company_id, type);

create table import_batch (
    id            bigserial primary key,
    company_id    bigint       not null references company (id) on delete cascade,
    kind          varchar(16)  not null,
    filename      varchar(255) not null,
    rows_read     integer      not null,
    rows_imported integer      not null,
    rows_skipped  integer      not null,
    created_at    timestamptz  not null default now()
);
