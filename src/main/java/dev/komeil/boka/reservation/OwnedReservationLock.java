package dev.komeil.boka.reservation;

import dev.komeil.boka.identity.Role;
import dev.komeil.boka.identity.UserRepository;
import dev.komeil.boka.slot.SlotRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OwnedReservationLock {
  private final SlotRepository slots;
  private final ReservationRepository reservations;
  private final UserRepository users;
  private final EntityManager entities;

  public OwnedReservationLock(
      SlotRepository slots,
      ReservationRepository reservations,
      UserRepository users,
      EntityManager entities) {
    this.slots = slots;
    this.reservations = reservations;
    this.users = users;
    this.entities = entities;
  }

  LockedReservation lock(UUID reservationId, String customerUsername) {
    Objects.requireNonNull(reservationId);
    var customer =
        users
            .findByUsername(customerUsername)
            .filter(user -> user.isEnabled() && user.getRole() == Role.CUSTOMER)
            .orElseThrow(() -> new AccessDeniedException("An enabled customer is required"));
    var slotId =
        reservations
            .findSlotIdByIdAndCustomerId(reservationId, customer.getId())
            .orElseThrow(this::notFound);
    var slot = slots.lockById(slotId).orElseThrow(this::notFound);
    entities.refresh(slot);
    var reservation =
        reservations
            .findByIdAndCustomerIdAndSlotId(reservationId, customer.getId(), slotId)
            .orElseThrow(this::notFound);
    entities.refresh(reservation);
    return new LockedReservation(reservation, slot.getStartsAt());
  }

  record LockedReservation(Reservation reservation, Instant slotStartsAt) {}

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
  }
}
