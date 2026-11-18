package dev.komeil.boka.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpiryService {
  private static final int BATCH_SIZE = 100;

  private final ReservationRepository reservations;

  public ReservationExpiryService(ReservationRepository reservations) {
    this.reservations = reservations;
  }

  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      isolation = Isolation.READ_COMMITTED,
      timeout = 10)
  public int expireBatch() {
    var candidates =
        reservations.lockSlotsForDueHolds(reservations.currentDatabaseTime(), BATCH_SIZE);
    if (candidates.isEmpty()) {
      return 0;
    }

    // The claim locks slots. A fresh read sees reservation commits made during selection.
    var holds = reservations.findAllById(candidates);
    var now = reservations.currentDatabaseTime();
    int expired = 0;
    for (var hold : holds) {
      if (hold.expireIfDue(now)) {
        expired++;
      }
    }
    return expired;
  }
}
