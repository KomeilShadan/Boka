package dev.komeil.boka.slot;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlotRepository extends JpaRepository<AppointmentSlot, UUID> {
  @Query(
      """
      select s from AppointmentSlot s
      where s.provider.id = :providerId and s.startsAt >= :from and s.startsAt < :to
        and s.startsAt > :now
      order by s.startsAt, s.id
      """)
  Page<AppointmentSlot> browse(
      @Param("providerId") UUID providerId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("now") Instant now,
      Pageable pageable);
}
