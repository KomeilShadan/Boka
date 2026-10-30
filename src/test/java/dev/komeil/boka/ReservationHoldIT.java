package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.*;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@TestPropertySource(properties = "boka.reservation.hold-duration=PT45S")
class ReservationHoldIT extends ApiIntegrationTest {
  @Autowired ReservationHoldService holds;
  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired DataSource database;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entities;

  @Test
  void createsOwnedHoldUsingDatabaseTimeAndConfiguredDuration() {
    var slot = slot();
    var before = databaseNow();
    var held = asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1"));
    var after = databaseNow();
    assertThat(AopUtils.isAopProxy(holds)).isTrue();
    assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
    assertThat(held.slotId()).isEqualTo(slot.getId());
    assertThat(held.createdAt()).isBetween(before, after);
    assertThat(held.createdAt()).isNotEqualTo(NOW);
    assertThat(Duration.between(held.createdAt(), held.expiresAt()))
        .isEqualTo(Duration.ofSeconds(45));
    assertThat(reservations.findById(held.id()).orElseThrow().getCustomerId())
        .isEqualTo(customer("customer1"));
  }

  @Test
  void methodSecurityRejectsAnonymousAdminsAndIdentitySpoofing() {
    var slot = slot();
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> holds.acquireHold(slot.getId(), "customer1"))
        .isInstanceOfAny(
            AuthenticationCredentialsNotFoundException.class, AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor("admin", "ADMIN", () -> holds.acquireHold(slot.getId(), "admin")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer2")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(reservations.count()).isZero();
  }

  @Test
  void rechecksEnabledStateAndRoleForPreviouslyAuthenticatedCustomers() {
    var slot = slot();
    try {
      jdbc.update("UPDATE users SET enabled = false WHERE username = 'customer1'");
      assertThatThrownBy(
              () -> asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1")))
          .isInstanceOf(AccessDeniedException.class);
      jdbc.update("UPDATE users SET enabled = true, role = 'ADMIN' WHERE username = 'customer1'");
      assertThatThrownBy(
              () -> asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1")))
          .isInstanceOf(AccessDeniedException.class);
    } finally {
      jdbc.update(
          "UPDATE users SET enabled = true, role = 'CUSTOMER' WHERE username = 'customer1'");
    }
    assertThat(reservations.count()).isZero();
  }

  @Test
  void rejectsMissingInactiveAndStartedSlots() {
    assertThatThrownBy(
            () -> asCustomer("customer1", () -> holds.acquireHold(UUID.randomUUID(), "customer1")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    var slot = slot();
    jdbc.update("UPDATE providers SET active = false WHERE id = ?", slot.getProvider().getId());
    assertThatThrownBy(
            () -> asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    jdbc.update("UPDATE providers SET active = true WHERE id = ?", slot.getProvider().getId());
    jdbc.update(
        "UPDATE appointment_slots SET starts_at = clock_timestamp() - interval '1 second' WHERE id = ?",
        slot.getId());
    assertThatThrownBy(
            () -> asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1")))
        .isInstanceOf(ReservationConflictException.class)
        .hasMessage("Slot has already started");
    assertThat(reservations.count()).isZero();
  }

  @Test
  void liveHoldRejectsAnotherRequestWithoutMovingItsDeadline() {
    var slot = slot();
    var first = asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1"));
    assertThatThrownBy(
            () -> asCustomer("customer2", () -> holds.acquireHold(slot.getId(), "customer2")))
        .isInstanceOf(ReservationConflictException.class)
        .hasMessage("Slot is unavailable");
    var stored = reservations.findById(first.id()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(stored.getExpiresAt()).isEqualTo(first.expiresAt());
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void overdueHoldIsExpiredAndRebookedAtomicallyWithoutAWorker() {
    var slot = slot();
    var old = overdueHold(slot);
    var next = asCustomer("customer2", () -> holds.acquireHold(slot.getId(), "customer2"));
    var expired = reservations.findById(old.getId()).orElseThrow();
    assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    assertThat(expired.getExpiresAt()).isEqualTo(old.getExpiresAt());
    assertThat(expired.getUpdatedAt()).isEqualTo(next.createdAt());
    assertThat(next.id()).isNotEqualTo(old.getId());
    assertThat(reservations.count()).isEqualTo(2);
    assertOneActive(slot.getId());
  }

  @Test
  void confirmedReservationDoesNotExpireWithTheOriginalDeadline() {
    var slot = slot();
    var now = databaseNow();
    var confirmed =
        Reservation.hold(
            slot.getId(),
            customer("customer1"),
            now.minusSeconds(600),
            now.minusSeconds(300),
            slot.getStartsAt());
    confirmed.confirm(now.minusSeconds(400), slot.getStartsAt());
    reservations.saveAndFlush(confirmed);
    assertThatThrownBy(
            () -> asCustomer("customer2", () -> holds.acquireHold(slot.getId(), "customer2")))
        .isInstanceOf(ReservationConflictException.class);
    assertThat(reservations.findById(confirmed.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void failedNewInsertRollsBackOverdueHoldCleanup() {
    var slot = slot();
    var old = overdueHold(slot);
    jdbc.execute(
        """
        CREATE FUNCTION review_reject_hold() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Injected hold insert failure';
        END $$
        """);
    jdbc.execute(
        "CREATE TRIGGER review_reject_hold BEFORE INSERT ON reservations FOR EACH ROW EXECUTE FUNCTION review_reject_hold()");
    try {
      assertThatThrownBy(
              () -> asCustomer("customer2", () -> holds.acquireHold(slot.getId(), "customer2")))
          .isInstanceOf(DataAccessException.class);
      assertThat(reservations.findById(old.getId()).orElseThrow().getStatus())
          .isEqualTo(ReservationStatus.HELD);
      assertThat(reservations.count()).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER review_reject_hold ON reservations");
      jdbc.execute("DROP FUNCTION review_reject_hold()");
    }
  }

  @Test
  void competingServiceTransactionsReturnOneHoldAndBusinessConflicts() throws Exception {
    var id = slot().getId();
    var outcomes = race(List.of(id, id, id));
    assertThat(outcomes).containsExactlyInAnyOrder("HELD", "CONFLICT", "CONFLICT");
    assertThat(reservations.count()).isEqualTo(1);
    assertOneActive(id);
  }

  @Test
  void transactionsForDifferentSlotsAllSucceed() throws Exception {
    var ids = List.of(slot().getId(), slot().getId(), slot().getId());
    assertThat(race(ids)).containsExactly("HELD", "HELD", "HELD");
    assertThat(reservations.count()).isEqualTo(3);
  }

  @Test
  void waitingRequestReloadsStateAndSamplesTimeAfterSlotLock() throws Exception {
    var slot = slot();
    var now = databaseNow();
    var old =
        reservations.saveAndFlush(
            Reservation.hold(
                slot.getId(),
                customer("customer1"),
                now.minusSeconds(60),
                now.plusSeconds(300),
                slot.getStartsAt()));
    var pid = new AtomicInteger();
    var started = new CountDownLatch(1);
    var transactionStart = new AtomicReference<Instant>();
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      lock(blocker, slot.getId());
      var worker =
          executor.submit(
              () ->
                  asCustomer(
                      "customer2",
                      () ->
                          transaction()
                              .execute(
                                  status -> {
                                    reservations.findById(old.getId()).orElseThrow();
                                    transactionStart.set(
                                        jdbc.queryForObject(
                                                "SELECT transaction_timestamp()",
                                                OffsetDateTime.class)
                                            .toInstant());
                                    pid.set(
                                        jdbc.queryForObject(
                                            "SELECT pg_backend_pid()", Integer.class));
                                    started.countDown();
                                    return holds.acquireHold(slot.getId(), "customer2");
                                  })));
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBlocked(pid.get());
        Instant cutoff;
        try (var statement = blocker.createStatement();
            var result = statement.executeQuery("SELECT clock_timestamp()")) {
          result.next();
          cutoff = result.getObject(1, OffsetDateTime.class).toInstant();
        }
        assertThat(cutoff).isAfter(transactionStart.get());
        try (var statement =
            blocker.prepareStatement("UPDATE reservations SET expires_at = ? WHERE id = ?")) {
          statement.setObject(1, cutoff.atOffset(ZoneOffset.UTC));
          statement.setObject(2, old.getId());
          statement.executeUpdate();
        }
        blocker.commit();
        var next = worker.get(15, TimeUnit.SECONDS);
        assertThat(next.createdAt()).isAfterOrEqualTo(cutoff);
        assertThat(next.createdAt()).isAfter(transactionStart.get());
        assertThat(reservations.findById(old.getId()).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
        assertOneActive(slot.getId());
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void blockedSlotDoesNotBlockAnUnrelatedSlot() throws Exception {
    var blocked = slot();
    var independent = slot();
    var pid = new AtomicInteger();
    var started = new CountDownLatch(1);
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      lock(blocker, blocked.getId());
      var worker =
          executor.submit(
              () ->
                  asCustomer(
                      "customer1",
                      () ->
                          transaction()
                              .execute(
                                  status -> {
                                    pid.set(
                                        jdbc.queryForObject(
                                            "SELECT pg_backend_pid()", Integer.class));
                                    started.countDown();
                                    return holds.acquireHold(blocked.getId(), "customer1");
                                  })));
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBlocked(pid.get());
        var next =
            asCustomer("customer2", () -> holds.acquireHold(independent.getId(), "customer2"));
        assertThat(next.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(worker.isDone()).isFalse();
        blocker.commit();
        assertThat(worker.get(15, TimeUnit.SECONDS).status()).isEqualTo(ReservationStatus.HELD);
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void lockTimeoutRemainsAnInfrastructureFailure() throws Exception {
    var slot = slot();
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      lock(blocker, slot.getId());
      try {
        var worker =
            executor.submit(
                () ->
                    asCustomer(
                        "customer1",
                        () ->
                            transaction()
                                .execute(
                                    status -> {
                                      jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                                      return holds.acquireHold(slot.getId(), "customer1");
                                    })));
        assertThatThrownBy(() -> worker.get(5, TimeUnit.SECONDS))
            .isInstanceOfSatisfying(
                ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOf(DataAccessException.class));
        assertThat(reservations.count()).isZero();
      } finally {
        blocker.rollback();
      }
    }
  }

  private List<String> race(List<UUID> ids) throws Exception {
    var pids = ConcurrentHashMap.<Integer>newKeySet();
    var barrier = new CyclicBarrier(ids.size());
    try (var executor = Executors.newFixedThreadPool(ids.size())) {
      var results = new ArrayList<Future<String>>();
      for (int i = 0; i < ids.size(); i++) {
        UUID id = ids.get(i);
        String username = i % 2 == 0 ? "customer1" : "customer2";
        results.add(
            executor.submit(
                () ->
                    asCustomer(
                        username,
                        () -> {
                          try {
                            return transaction()
                                .execute(
                                    status -> {
                                      pids.add(
                                          jdbc.queryForObject(
                                              "SELECT pg_backend_pid()", Integer.class));
                                      await(barrier);
                                      return holds.acquireHold(id, username).status().name();
                                    });
                          } catch (ReservationConflictException expected) {
                            return "CONFLICT";
                          }
                        })));
      }
      var outcomes = new ArrayList<String>();
      for (var result : results) outcomes.add(result.get(20, TimeUnit.SECONDS));
      assertThat(pids).hasSize(ids.size());
      return outcomes;
    }
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setTimeout(10);
    return transaction;
  }

  private void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new IllegalStateException("Concurrent test coordination failed", exception);
    }
  }

  private void awaitBlocked(int pid) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < until) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid)))
        return;
      Thread.onSpinWait();
    }
    fail("Request did not reach a PostgreSQL row-lock wait");
  }

  private void lock(Connection connection, UUID slotId) throws Exception {
    try (var statement =
        connection.prepareStatement("SELECT id FROM appointment_slots WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, slotId);
      statement.executeQuery().close();
    }
  }

  private AppointmentSlot slot() {
    var now = databaseNow();
    var provider = providers.saveAndFlush(new Provider("Hold service fixture"));
    return slots.saveAndFlush(
        new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now));
  }

  private Reservation overdueHold(AppointmentSlot slot) {
    var now = databaseNow();
    return reservations.saveAndFlush(
        Reservation.hold(
            slot.getId(),
            customer("customer1"),
            now.minusSeconds(600),
            now.minusSeconds(300),
            slot.getStartsAt()));
  }

  private UUID customer(String username) {
    return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  private void assertOneActive(UUID slotId) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE slot_id = ? AND status IN ('HELD','CONFIRMED')",
                Integer.class,
                slotId))
        .isEqualTo(1);
  }

  private <T> T asCustomer(String username, Supplier<T> action) {
    return asActor(username, "CUSTOMER", action);
  }

  private <T> T asActor(String username, String role, Supplier<T> action) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
