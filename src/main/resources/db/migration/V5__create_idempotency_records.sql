CREATE TABLE idempotency_records (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    operation varchar(40) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    reservation_id uuid REFERENCES reservations(id),
    response_status integer,
    response_body text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT idempotency_actor_operation_key UNIQUE (actor_id, operation, request_key),
    CONSTRAINT idempotency_valid_operation CHECK (operation = 'reservation.create.v1'),
    CONSTRAINT idempotency_valid_key CHECK (request_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT idempotency_valid_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_finite_time CHECK (isfinite(created_at)),
    CONSTRAINT idempotency_response_complete CHECK (
        (reservation_id IS NULL AND response_status IS NULL AND response_body IS NULL)
        OR (reservation_id IS NOT NULL AND response_status IS NOT NULL
            AND response_status = 201 AND response_body IS NOT NULL
            AND jsonb_typeof(response_body::jsonb) = 'object')
    )
);
