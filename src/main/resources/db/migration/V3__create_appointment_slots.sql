CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE appointment_slots (
    id uuid PRIMARY KEY,
    provider_id uuid NOT NULL REFERENCES providers(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT appointment_slots_valid_interval CHECK (ends_at > starts_at),
    CONSTRAINT appointment_slots_finite CHECK (isfinite(starts_at) AND isfinite(ends_at)),
    CONSTRAINT appointment_slots_no_overlap EXCLUDE USING gist (
        provider_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

CREATE INDEX appointment_slots_provider_start ON appointment_slots(provider_id, starts_at, id);
