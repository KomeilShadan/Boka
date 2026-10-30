package dev.komeil.boka.reservation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boka.reservation")
public record ReservationProperties(Duration holdDuration) {
  public ReservationProperties {
    if (holdDuration == null
        || holdDuration.compareTo(Duration.ofSeconds(1)) < 0
        || holdDuration.compareTo(Duration.ofDays(1)) > 0
        || holdDuration.getNano() % 1000 != 0) {
      throw new IllegalArgumentException(
          "Hold duration must be between 1 second and 24 hours at microsecond precision");
    }
  }
}
