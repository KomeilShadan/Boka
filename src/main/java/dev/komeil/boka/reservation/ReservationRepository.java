package dev.komeil.boka.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  @Query("select r.slotId from Reservation r where r.id = :id and r.customerId = :customerId")
  Optional<UUID> findSlotIdByIdAndCustomerId(
      @Param("id") UUID id, @Param("customerId") UUID customerId);

  Optional<Reservation> findByIdAndCustomerIdAndSlotId(UUID id, UUID customerId, UUID slotId);

  @Query(
      value =
          "SELECT * FROM reservations WHERE slot_id = :slotId AND status IN ('HELD', 'CONFIRMED')",
      nativeQuery = true)
  Optional<Reservation> findActiveBySlotId(@Param("slotId") UUID slotId);

  @Query(
      value =
          """
          SELECT r.id
          FROM reservations r
          JOIN appointment_slots s ON s.id = r.slot_id
          WHERE r.status = 'HELD' AND r.expires_at <= :cutoff
          ORDER BY r.expires_at, r.id
          LIMIT :batchSize
          FOR UPDATE OF s SKIP LOCKED
          """,
      nativeQuery = true)
  List<UUID> lockSlotsForDueHolds(
      @Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

  @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTime();
}
