package dev.komeil.boka;

import static dev.komeil.boka.reservation.ReservationStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.Reservation;
import dev.komeil.boka.reservation.ReservationExpiryService;
import dev.komeil.boka.reservation.ReservationRepository;
import dev.komeil.boka.reservation.ReservationStatus;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ReservationExpiryIT extends ApiIntegrationTest {
  @Autowired ReservationExpiryService expiry;
  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired PostgreSQLContainer postgres;

  private UUID customerId;
  private Provider provider;
  private Instant now;
  private Instant slotStart;

  @BeforeEach
  void prepareFixture() {
    customerId =
        jdbc.queryForObject("SELECT id FROM users WHERE username = 'customer1'", UUID.class);
    provider = providers.saveAndFlush(new Provider("Expiry fixture"));
    now = reservations.currentDatabaseTime();
    slotStart = now.plusSeconds(3600);
  }

  @Test
  void expiresOnlyOverdueHoldsAndPreservesTheirHistoryOnRetry() {
    var due = reservation(HELD, now);
    var future = reservation(HELD, now.plusSeconds(600));
    var confirmed = reservation(CONFIRMED, now);
    var cancelled = reservation(CANCELLED, now);
    var expired = reservation(EXPIRED, now);

    assertThat(expiry.expireBatch()).isEqualTo(1);
    var updated = stored(due);
    assertThat(updated.getStatus()).isEqualTo(EXPIRED);
    assertThat(updated.getUpdatedAt()).isBetween(now, reservations.currentDatabaseTime());
    assertThat(updated.getExpiresAt()).isEqualTo(now);
    assertThat(reservations.findActiveBySlotId(updated.getSlotId())).isEmpty();

    assertThat(expiry.expireBatch()).isZero();
    assertThat(stored(due).getUpdatedAt()).isEqualTo(updated.getUpdatedAt());
    assertThat(stored(future).getStatus()).isEqualTo(HELD);
    assertThat(stored(confirmed).getStatus()).isEqualTo(CONFIRMED);
    assertThat(stored(cancelled).getStatus()).isEqualTo(CANCELLED);
    assertThat(stored(expired).getUpdatedAt()).isEqualTo(now.minusSeconds(120));
  }

  @Test
  void limitsOneBatchAndProcessesTheEarliestDeadlinesFirst() {
    var chronologicalIds = new ArrayList<UUID>();
    for (int i = 0; i < 101; i++) {
      chronologicalIds.add(reservation(HELD, now.minusSeconds(101 - i)));
    }

    assertThat(expiry.expireBatch()).isEqualTo(100);
    assertThat(stored(chronologicalIds.getLast()).getStatus()).isEqualTo(HELD);
    assertThat(reservations.findAllById(chronologicalIds.subList(0, 100)))
        .hasSize(100)
        .allMatch(reservation -> reservation.getStatus() == EXPIRED);
    assertThat(expiry.expireBatch()).isEqualTo(1);
    assertThat(stored(chronologicalIds.getLast()).getStatus()).isEqualTo(EXPIRED);
  }

  @Test
  void skipsASlotLockedByAnotherTransactionAndRetriesItAfterRelease() {
    var locked = reservation(HELD, now.minusSeconds(1));
    var available = reservation(HELD, now);
    var lockedSlotId = stored(locked).getSlotId();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              assertThat(slots.lockById(lockedSlotId)).isPresent();
              assertThat(expiry.expireBatch()).isEqualTo(1);
              assertThat(stored(locked).getStatus()).isEqualTo(HELD);
              assertThat(stored(available).getStatus()).isEqualTo(EXPIRED);
            });

    assertThat(expiry.expireBatch()).isEqualTo(1);
    assertThat(stored(locked).getStatus()).isEqualTo(EXPIRED);
  }

  @Test
  void concurrentWorkersExpireEachHoldOnlyOnce() throws Exception {
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 20; i++) {
      ids.add(reservation(HELD, now));
    }

    var ready = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () -> {
                ready.await(5, TimeUnit.SECONDS);
                return expiry.expireBatch();
              });
      var second =
          workers.submit(
              () -> {
                ready.await(5, TimeUnit.SECONDS);
                return expiry.expireBatch();
              });
      assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS))
          .isEqualTo(ids.size());
    }

    assertThat(reservations.findAllById(ids))
        .hasSize(ids.size())
        .allMatch(reservation -> reservation.getStatus() == EXPIRED);
    assertThat(expiry.expireBatch()).isZero();
  }

  @Test
  void failedBatchRollsBackAndTheNextBatchCanRetryIt() {
    var first = reservation(HELD, now.minusSeconds(1));
    var second = reservation(HELD, now);
    jdbc.execute(
        "ALTER TABLE reservations ADD CONSTRAINT test_reject_expiry "
            + "CHECK (status <> 'EXPIRED' OR id <> '"
            + second
            + "')");
    try {
      assertThatThrownBy(expiry::expireBatch).isInstanceOf(DataIntegrityViolationException.class);
      assertThat(stored(first).getStatus()).isEqualTo(HELD);
      assertThat(stored(second).getStatus()).isEqualTo(HELD);
    } finally {
      jdbc.execute("ALTER TABLE reservations DROP CONSTRAINT test_reject_expiry");
    }
    assertThat(expiry.expireBatch()).isEqualTo(2);
  }

  @Test
  void schedulerInANewApplicationProcessesExistingOverdueHolds() {
    var due = reservation(HELD, now);
    assertThat(stored(due).getStatus()).isEqualTo(HELD);
    try (var recovered =
        new SpringApplicationBuilder(BokaApplication.class)
            .run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.profiles.active=default",
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--management.server.port=0",
                "--management.server.address=127.0.0.1",
                "--boka.reservation.expiry.enabled=true",
                "--boka.reservation.expiry.delay=PT0.1S")) {
      assertThat(recovered.isRunning()).isTrue();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(stored(due).getStatus()).isEqualTo(EXPIRED));
    }
  }

  private UUID reservation(ReservationStatus status, Instant deadline) {
    var slot =
        slots.saveAndFlush(
            new AppointmentSlot(provider, slotStart, slotStart.plusSeconds(60), now));
    slotStart = slotStart.plusSeconds(60);
    var id = UUID.randomUUID();
    var createdAt = now.minusSeconds(120).atOffset(ZoneOffset.UTC);
    jdbc.update(
        """
        INSERT INTO reservations (id, slot_id, customer_id, status, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        slot.getId(),
        customerId,
        status.name(),
        deadline.atOffset(ZoneOffset.UTC),
        createdAt,
        createdAt);
    return id;
  }

  private Reservation stored(UUID id) {
    return reservations.findById(id).orElseThrow();
  }
}
