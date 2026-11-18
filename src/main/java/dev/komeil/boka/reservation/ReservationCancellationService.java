package dev.komeil.boka.reservation;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCancellationService {
  private final OwnedReservationLock ownedReservations;
  private final ReservationRepository reservations;

  public ReservationCancellationService(
      OwnedReservationLock ownedReservations, ReservationRepository reservations) {
    this.ownedReservations = ownedReservations;
    this.reservations = reservations;
  }

  @PreAuthorize("hasRole('CUSTOMER') and #customerUsername == authentication.name")
  @Transactional(timeout = 10)
  public ReservationResponse cancel(UUID reservationId, String customerUsername) {
    var locked = ownedReservations.lock(reservationId, customerUsername);
    var reservation = locked.reservation();
    reservation.cancel(reservations.currentDatabaseTime(), locked.slotStartsAt());
    reservations.flush();
    return ReservationResponse.from(reservation);
  }
}
