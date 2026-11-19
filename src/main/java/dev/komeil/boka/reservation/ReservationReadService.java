package dev.komeil.boka.reservation;

import dev.komeil.boka.identity.Role;
import dev.komeil.boka.identity.UserRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationReadService {
  private final UserRepository users;
  private final ReservationRepository reservations;

  public ReservationReadService(UserRepository users, ReservationRepository reservations) {
    this.users = users;
    this.reservations = reservations;
  }

  @PreAuthorize("hasRole('CUSTOMER') and #customerUsername == authentication.name")
  @Transactional(readOnly = true, timeout = 10)
  public ReservationResponse read(UUID reservationId, String customerUsername) {
    var customer =
        users
            .findByUsername(customerUsername)
            .filter(user -> user.isEnabled() && user.getRole() == Role.CUSTOMER)
            .orElseThrow(() -> new AccessDeniedException("An enabled customer is required"));
    return reservations
        .findByIdAndCustomerId(reservationId, customer.getId())
        .map(ReservationResponse::from)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
  }
}
