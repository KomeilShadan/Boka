package dev.komeil.boka.reservation;

import dev.komeil.boka.identity.Role;
import dev.komeil.boka.identity.UserRepository;
import dev.komeil.boka.slot.SlotRepository;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationHoldService {
  private final SlotRepository slots;
  private final ReservationRepository reservations;
  private final UserRepository users;
  private final ReservationProperties properties;
  private final EntityManager entities;

  public ReservationHoldService(
      SlotRepository slots,
      ReservationRepository reservations,
      UserRepository users,
      ReservationProperties properties,
      EntityManager entities) {
    this.slots = slots;
    this.reservations = reservations;
    this.users = users;
    this.properties = properties;
    this.entities = entities;
  }

  @PreAuthorize("hasRole('CUSTOMER') and #customerUsername == authentication.name")
  @Transactional(timeout = 10)
  public ReservationResponse acquireHold(UUID slotId, String customerUsername) {
    Objects.requireNonNull(slotId);
    var customer =
        users
            .findByUsername(customerUsername)
            .filter(user -> user.isEnabled() && user.getRole() == Role.CUSTOMER)
            .orElseThrow(() -> new AccessDeniedException("An enabled customer is required"));
    var slot =
        slots
            .lockById(slotId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found"));
    entities.refresh(slot);
    if (!slot.getProvider().isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found");
    }
    var active = reservations.findActiveBySlotId(slotId);
    active.ifPresent(entities::refresh);
    var now = reservations.currentDatabaseTime();
    if (!now.isBefore(slot.getStartsAt())) {
      throw new ReservationConflictException("Slot has already started");
    }
    if (active.isPresent()) {
      if (!active.get().expireIfDue(now)) {
        throw new ReservationConflictException("Slot is unavailable");
      }
      // Release the partial-index entry before Hibernate can schedule a new INSERT.
      reservations.flush();
    }
    var held =
        Reservation.hold(
            slotId, customer.getId(), now, now.plus(properties.holdDuration()), slot.getStartsAt());
    return ReservationResponse.from(reservations.saveAndFlush(held));
  }
}
