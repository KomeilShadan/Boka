package dev.komeil.boka.slot;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record CreateSlotRequest(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt) {}
