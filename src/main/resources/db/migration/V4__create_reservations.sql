CREATE TABLE reservations (
    id uuid PRIMARY KEY,
    slot_id uuid NOT NULL REFERENCES appointment_slots(id),
    customer_id uuid NOT NULL REFERENCES users(id),
    status varchar(20) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT reservations_valid_status CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT reservations_valid_deadline CHECK (expires_at > created_at),
    CONSTRAINT reservations_valid_update_time CHECK (updated_at >= created_at),
    CONSTRAINT reservations_finite_times CHECK (
        isfinite(expires_at) AND isfinite(created_at) AND isfinite(updated_at)
    )
);

CREATE UNIQUE INDEX reservations_one_active_per_slot ON reservations(slot_id)
    WHERE status IN ('HELD', 'CONFIRMED');
