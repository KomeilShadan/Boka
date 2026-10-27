CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(80) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    role varchar(20) NOT NULL CHECK (role IN ('ADMIN', 'CUSTOMER')),
    enabled boolean NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (username = lower(trim(username)) AND length(username) > 0)
);
