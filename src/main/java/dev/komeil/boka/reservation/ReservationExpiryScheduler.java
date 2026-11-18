package dev.komeil.boka.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "boka.reservation.expiry", name = "enabled", havingValue = "true")
class ReservationExpiryScheduler {
  private final ReservationExpiryService expiry;

  ReservationExpiryScheduler(ReservationExpiryService expiry) {
    this.expiry = expiry;
  }

  @Scheduled(
      initialDelayString = "${boka.reservation.expiry.delay}",
      fixedDelayString = "${boka.reservation.expiry.delay}")
  void expireDueHolds() {
    expiry.expireBatch();
  }
}
