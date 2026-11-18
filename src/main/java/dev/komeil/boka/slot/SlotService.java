package dev.komeil.boka.slot;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.provider.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SlotService {
  // PostgreSQL's finite timestamptz range in UTC, at microsecond precision.
  private static final Instant MIN_POSTGRES_TIMESTAMP = Instant.parse("-4713-11-24T00:00:00Z");
  private static final Instant MAX_POSTGRES_TIMESTAMP =
      Instant.parse("+294276-12-31T23:59:59.999999Z");

  private final ProviderRepository providers;
  private final SlotRepository slots;
  private final Clock clock;

  public SlotService(ProviderRepository providers, SlotRepository slots, Clock clock) {
    this.providers = providers;
    this.slots = slots;
    this.clock = clock;
  }

  @Transactional(timeout = 10)
  public SlotResponse create(UUID providerId, CreateSlotRequest request) {
    // Serialize competing inserts before they enter the GiST exclusion check.
    var provider =
        providers
            .lockById(providerId)
            .filter(Provider::isActive)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));
    AppointmentSlot slot;
    try {
      slot =
          new AppointmentSlot(
              provider,
              request.startsAt().toInstant(),
              request.endsAt().toInstant(),
              clock.instant());
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
    requireSupportedTimestamps(slot.getStartsAt(), slot.getEndsAt(), MAX_POSTGRES_TIMESTAMP);
    return SlotResponse.from(slots.saveAndFlush(slot));
  }

  @Transactional(readOnly = true)
  public SlotPage browse(UUID providerId, Instant from, Instant to, int page, int size) {
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Browse window must be positive and at most 31 days");
    }
    // pgJDBC rounds fractional microseconds when binding browse parameters.
    requireSupportedTimestamps(from, to, MAX_POSTGRES_TIMESTAMP.plusNanos(499));
    activeProvider(providerId);
    var now = slots.currentDatabaseTime();
    var result = slots.browse(providerId, from, to, now, PageRequest.of(page, size));
    return new SlotPage(
        result.getContent(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  private static void requireSupportedTimestamps(Instant start, Instant end, Instant latest) {
    if (start.isBefore(MIN_POSTGRES_TIMESTAMP) || end.isAfter(latest)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Slot timestamps are outside the supported range");
    }
  }

  private Provider activeProvider(UUID id) {
    return providers
        .findById(id)
        .filter(Provider::isActive)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));
  }
}
