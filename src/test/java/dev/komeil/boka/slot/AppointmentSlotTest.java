package dev.komeil.boka.slot;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AppointmentSlotTest {
  private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
  private final Provider provider = new Provider("Fictional provider");

  @Test
  void normalizesToPostgresMicroseconds() {
    var slot =
        new AppointmentSlot(
            provider, now.plusSeconds(60).plusNanos(123456789), now.plusSeconds(120), now);
    assertThat(slot.getStartsAt()).isEqualTo(now.plusSeconds(60).plusNanos(123456000));
    assertThat(slot.getId()).isNotNull();
  }

  @Test
  void rejectsPastOrExactlyNowAndEmptyOrReversedIntervals() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AppointmentSlot(provider, now, now.plusSeconds(60), now));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new AppointmentSlot(provider, now.minusSeconds(1), now.plusSeconds(60), now));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new AppointmentSlot(provider, now.plusSeconds(60), now.plusSeconds(60), now));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new AppointmentSlot(provider, now.plusSeconds(60), now.plusSeconds(30), now));
  }

  @Test
  void rejectsIntervalsThatCollapseAtDatabasePrecision() {
    var start = now.plusSeconds(1);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AppointmentSlot(provider, start, start.plusNanos(1), now));
  }
}
