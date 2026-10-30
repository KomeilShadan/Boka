package dev.komeil.boka.reservation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  @Query(
      value =
          "SELECT * FROM reservations WHERE slot_id = :slotId AND status IN ('HELD', 'CONFIRMED')",
      nativeQuery = true)
  Optional<Reservation> findActiveBySlotId(@Param("slotId") UUID slotId);

  @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTime();
}
