package dev.komeil.boka.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID id,
    UUID slotId,
    ReservationStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt) {
  static ReservationResponse from(Reservation reservation) {
    return new ReservationResponse(
        reservation.getId(),
        reservation.getSlotId(),
        reservation.getStatus(),
        reservation.getExpiresAt(),
        reservation.getCreatedAt(),
        reservation.getUpdatedAt());
  }
}
