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
    var provider = activeProvider(providerId);
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
    return SlotResponse.from(slots.saveAndFlush(slot));
  }

  @Transactional(readOnly = true)
  public SlotPage browse(UUID providerId, Instant from, Instant to, int page, int size) {
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Browse window must be positive and at most 31 days");
    }
    activeProvider(providerId);
    var result = slots.browse(providerId, from, to, clock.instant(), PageRequest.of(page, size));
    return new SlotPage(
        result.getContent().stream().map(SlotResponse::from).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  private Provider activeProvider(UUID id) {
    return providers
        .findById(id)
        .filter(Provider::isActive)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found"));
  }
}
