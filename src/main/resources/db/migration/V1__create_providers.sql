CREATE TABLE providers (
    id uuid PRIMARY KEY,
    display_name varchar(120) NOT NULL CHECK (length(trim(display_name)) > 0),
    active boolean NOT NULL
);
