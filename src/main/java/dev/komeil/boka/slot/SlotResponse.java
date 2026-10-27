package dev.komeil.boka.slot;

import java.time.Instant;
import java.util.UUID;

public record SlotResponse(
    UUID id, UUID providerId, Instant startsAt, Instant endsAt, boolean available) {
  static SlotResponse from(AppointmentSlot slot) {
    return new SlotResponse(
        slot.getId(), slot.getProvider().getId(), slot.getStartsAt(), slot.getEndsAt(), true);
  }
}
