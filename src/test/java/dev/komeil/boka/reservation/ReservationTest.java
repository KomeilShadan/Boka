package dev.komeil.boka.reservation;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {
  private final UUID slotId = UUID.randomUUID();
  private final UUID customerId = UUID.randomUUID();
  private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
  private final Instant deadline = now.plusSeconds(300);
  private final Instant slotStartsAt = now.plusSeconds(3600);

  @Test
  void newHoldHasStableIdentityAndDatabasePrecision() {
    var reservation =
        Reservation.hold(
            slotId, customerId, now.plusNanos(123456789), deadline.plusNanos(789), slotStartsAt);
    assertThat(reservation.getId()).isNotNull();
    assertThat(reservation.getSlotId()).isEqualTo(slotId);
    assertThat(reservation.getCustomerId()).isEqualTo(customerId);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(reservation.getCreatedAt()).isEqualTo(now.plusNanos(123456000));
    assertThat(reservation.getUpdatedAt()).isEqualTo(reservation.getCreatedAt());
    assertThat(reservation.getExpiresAt()).isEqualTo(deadline);
  }

  @Test
  void rejectsNonPositiveOrSubMicrosecondHoldLength() {
    for (Instant invalid : new Instant[] {now.minusSeconds(1), now, now.plusNanos(999)}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> Reservation.hold(slotId, customerId, now, invalid, slotStartsAt));
    }
  }

  @Test
  void rejectsHoldAtOrAfterSlotStart() {
    for (Instant start : new Instant[] {now.minusSeconds(1), now}) {
      assertThatThrownBy(() -> Reservation.hold(slotId, customerId, now, deadline, start))
          .isInstanceOf(ReservationConflictException.class);
    }
  }

  @Test
  void rejectsMissingIdentitiesOrTimes() {
    assertThatNullPointerException()
        .isThrownBy(() -> Reservation.hold(null, customerId, now, deadline, slotStartsAt));
    assertThatNullPointerException()
        .isThrownBy(() -> Reservation.hold(slotId, null, now, deadline, slotStartsAt));
    assertThatNullPointerException()
        .isThrownBy(() -> Reservation.hold(slotId, customerId, null, deadline, slotStartsAt));
    assertThatNullPointerException()
        .isThrownBy(() -> Reservation.hold(slotId, customerId, now, null, slotStartsAt));
    assertThatNullPointerException()
        .isThrownBy(() -> Reservation.hold(slotId, customerId, now, deadline, null));
  }

  @Test
  void confirmsImmediatelyBeforeDeadlineWithoutExtendingHold() {
    var reservation = held();
    var id = reservation.getId();
    var confirmation = deadline.minusNanos(1000);
    reservation.confirm(confirmation, slotStartsAt);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(reservation.getUpdatedAt()).isEqualTo(confirmation);
    assertThat(reservation.getCreatedAt()).isEqualTo(now);
    assertThat(reservation.getExpiresAt()).isEqualTo(deadline);
    assertThat(reservation.getId()).isEqualTo(id);
  }

  @Test
  void rejectsConfirmationAtOrAfterDeadlineWithoutChangingState() {
    for (Instant confirmation : new Instant[] {deadline, deadline.plusNanos(1000)}) {
      var reservation = held();
      assertThatThrownBy(() -> reservation.confirm(confirmation, slotStartsAt))
          .isInstanceOf(ReservationConflictException.class)
          .hasMessage("Hold has expired");
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
      assertThat(reservation.getUpdatedAt()).isEqualTo(now);
    }
  }

  @Test
  void rejectsConfirmationAtOrAfterSlotStartEvenBeforeDeadline() {
    for (Instant confirmation : new Instant[] {now.plusSeconds(60), now.plusSeconds(61)}) {
      var reservation = Reservation.hold(slotId, customerId, now, deadline, now.plusSeconds(60));
      assertThatThrownBy(() -> reservation.confirm(confirmation, now.plusSeconds(60)))
          .isInstanceOf(ReservationConflictException.class)
          .hasMessage("Slot has already started");
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
    }
  }

  @Test
  void repeatedConfirmationReturnsCurrentStateWithoutMutatingTimestamps() {
    var reservation = held();
    var confirmation = now.plusSeconds(60);
    reservation.confirm(confirmation, slotStartsAt);
    reservation.confirm(slotStartsAt.plusSeconds(60), slotStartsAt);
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(reservation.getUpdatedAt()).isEqualTo(confirmation);
    assertThat(reservation.getExpiresAt()).isEqualTo(deadline);
  }

  @Test
  void rejectsConfirmationBeforeCreation() {
    var reservation = held();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> reservation.confirm(now.minusSeconds(1), slotStartsAt));
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void expiresOnlyHeldReservationsAtOrAfterDeadline() {
    var reservation = held();
    assertThat(reservation.expireIfDue(deadline.minusNanos(1000))).isFalse();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(reservation.expireIfDue(deadline)).isTrue();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    assertThat(reservation.getUpdatedAt()).isEqualTo(deadline);
    assertThat(reservation.expireIfDue(deadline.plusSeconds(1))).isFalse();
    assertThat(reservation.getUpdatedAt()).isEqualTo(deadline);
  }

  @Test
  void neverExpiresConfirmedReservationsWithTheOldHoldDeadline() {
    var reservation = held();
    reservation.confirm(now.plusSeconds(1), slotStartsAt);
    assertThat(reservation.expireIfDue(deadline.plusSeconds(1))).isFalse();
    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(reservation.getUpdatedAt()).isEqualTo(now.plusSeconds(1));
  }

  private Reservation held() {
    return Reservation.hold(slotId, customerId, now, deadline, slotStartsAt);
  }
}
