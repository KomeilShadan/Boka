CREATE INDEX reservations_held_expiry_idx ON reservations (expires_at, id)
    WHERE status = 'HELD';
