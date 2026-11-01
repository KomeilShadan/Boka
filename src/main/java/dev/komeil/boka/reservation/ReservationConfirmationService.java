package dev.komeil.boka.reservation;

import dev.komeil.boka.identity.Role;
import dev.komeil.boka.identity.UserRepository;
import dev.komeil.boka.slot.SlotRepository;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationConfirmationService {
  private final SlotRepository slots;
  private final ReservationRepository reservations;
  private final UserRepository users;
  private final EntityManager entities;

  public ReservationConfirmationService(
      SlotRepository slots,
      ReservationRepository reservations,
      UserRepository users,
      EntityManager entities) {
    this.slots = slots;
    this.reservations = reservations;
    this.users = users;
    this.entities = entities;
  }

  @PreAuthorize("hasRole('CUSTOMER') and #customerUsername == authentication.name")
  @Transactional(timeout = 10)
  public ReservationResponse confirm(UUID reservationId, String customerUsername) {
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
    reservation.confirm(reservations.currentDatabaseTime(), slot.getStartsAt());
    reservations.flush();
    return ReservationResponse.from(reservation);
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
  }
}
