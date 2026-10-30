package dev.komeil.boka.reservation;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReservationPropertiesTest {
  @Test
  void acceptsPositiveBoundedDurationsAtDatabasePrecision() {
    for (Duration duration :
        new Duration[] {Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofDays(1)}) {
      assertThat(new ReservationProperties(duration).holdDuration()).isEqualTo(duration);
    }
  }

  @Test
  void rejectsMissingNonPositiveUnboundedAndSubMicrosecondValues() {
    for (Duration duration :
        new Duration[] {
          null,
          Duration.ZERO,
          Duration.ofSeconds(-1),
          Duration.ofMillis(999),
          Duration.ofDays(1).plusSeconds(1),
          Duration.ofSeconds(1).plusNanos(1)
        }) {
      assertThatIllegalArgumentException().isThrownBy(() -> new ReservationProperties(duration));
    }
  }
}
