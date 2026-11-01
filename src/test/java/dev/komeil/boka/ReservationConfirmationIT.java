package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.*;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.sql.Connection;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReservationConfirmationIT extends ApiIntegrationTest {
  @Autowired ReservationConfirmationService confirmations;
  @Autowired ReservationHoldService holds;
  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired DataSource database;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void confirmsOwnedHoldUsingDatabaseTimeWithoutExtendingIt() {
    var slot = slot();
    var held = asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1"));
    var before = databaseNow();
    var confirmed = asCustomer("customer1", () -> confirmations.confirm(held.id(), "customer1"));
    var after = databaseNow();
    assertThat(AopUtils.isAopProxy(confirmations)).isTrue();
    assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(confirmed.id()).isEqualTo(held.id());
    assertThat(confirmed.slotId()).isEqualTo(held.slotId());
    assertThat(confirmed.createdAt()).isEqualTo(held.createdAt());
    assertThat(confirmed.expiresAt()).isEqualTo(held.expiresAt());
    assertThat(confirmed.updatedAt()).isBetween(before, after).isNotEqualTo(NOW);
    var stored = reservations.findById(held.id()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(stored.getUpdatedAt()).isEqualTo(confirmed.updatedAt());
    assertThat(stored.getCustomerId()).isEqualTo(customer("customer1"));
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void missingAndOtherCustomersReservationsHaveTheSameNotFoundResult() {
    var held = liveHold(slot());
    assertNotFound(
        () -> asCustomer("customer2", () -> confirmations.confirm(held.getId(), "customer2")));
    assertNotFound(
        () -> asCustomer("customer2", () -> confirmations.confirm(UUID.randomUUID(), "customer2")));
    assertThat(reservations.findById(held.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void methodSecurityRejectsAnonymousAdminsAndSpoofedIdentities() {
    var held = liveHold(slot());
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> confirmations.confirm(held.getId(), "customer1"))
        .isInstanceOfAny(
            AuthenticationCredentialsNotFoundException.class, AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor("admin", "ADMIN", () -> confirmations.confirm(held.getId(), "admin")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> asCustomer("customer2", () -> confirmations.confirm(held.getId(), "customer1")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(reservations.findById(held.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void rechecksPersistedAccountStateForPreviouslyAuthenticatedCustomers() {
    var held = liveHold(slot());
    try {
      jdbc.update("UPDATE users SET enabled = false WHERE username = 'customer1'");
      assertThatThrownBy(
              () -> asCustomer("customer1", () -> confirmations.confirm(held.getId(), "customer1")))
          .isInstanceOf(AccessDeniedException.class);
      jdbc.update("UPDATE users SET enabled = true, role = 'ADMIN' WHERE username = 'customer1'");
      assertThatThrownBy(
              () -> asCustomer("customer1", () -> confirmations.confirm(held.getId(), "customer1")))
          .isInstanceOf(AccessDeniedException.class);
    } finally {
      jdbc.update(
          "UPDATE users SET enabled = true, role = 'CUSTOMER' WHERE username = 'customer1'");
    }
    assertThat(reservations.findById(held.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void rejectsOverdueConfirmationWithoutClaimingCommittedCleanup() {
    var held = overdueHold(slot());
    assertThatThrownBy(
            () -> asCustomer("customer1", () -> confirmations.confirm(held.getId(), "customer1")))
        .isInstanceOf(ReservationConflictException.class)
        .hasMessage("Hold has expired");
    var stored = reservations.findById(held.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(stored.getUpdatedAt()).isEqualTo(held.getUpdatedAt());
    assertThat(stored.getExpiresAt()).isEqualTo(held.getExpiresAt());
  }

  @Test
  void rejectsStartedSlotEvenIfTheHoldDeadlineIsStillFuture() {
    var slot = slot();
    var held = liveHold(slot);
    jdbc.update(
        "UPDATE appointment_slots SET starts_at = clock_timestamp() - interval '1 second' WHERE id = ?",
        slot.getId());
    assertThatThrownBy(
            () -> asCustomer("customer1", () -> confirmations.confirm(held.getId(), "customer1")))
        .isInstanceOf(ReservationConflictException.class)
        .hasMessage("Slot has already started");
    assertThat(reservations.findById(held.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void terminalReservationsCannotBeRevived() {
    for (var terminal : List.of(ReservationStatus.EXPIRED, ReservationStatus.CANCELLED)) {
      var held = liveHold(slot());
      jdbc.update("UPDATE reservations SET status = ? WHERE id = ?", terminal.name(), held.getId());
      assertThatThrownBy(
              () -> asCustomer("customer1", () -> confirmations.confirm(held.getId(), "customer1")))
          .isInstanceOf(ReservationConflictException.class)
          .hasMessage("Reservation cannot be confirmed from " + terminal);
      assertThat(reservations.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(terminal);
    }
  }

  @Test
  void alreadyConfirmedRetryPreservesStateAfterTheOriginalDeadlineAndSlotStart() {
    var slot = slot();
    var now = databaseNow();
    var old =
        Reservation.hold(
            slot.getId(),
            customer("customer1"),
            now.minusSeconds(600),
            now.minusSeconds(300),
            slot.getStartsAt());
    old.confirm(now.minusSeconds(400), slot.getStartsAt());
    reservations.saveAndFlush(old);
    jdbc.update(
        "UPDATE appointment_slots SET starts_at = clock_timestamp() - interval '1 second' WHERE id = ?",
        slot.getId());
    var response = asCustomer("customer1", () -> confirmations.confirm(old.getId(), "customer1"));
    assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(response.id()).isEqualTo(old.getId());
    assertThat(response.expiresAt()).isEqualTo(old.getExpiresAt());
    assertThat(response.createdAt()).isEqualTo(old.getCreatedAt());
    assertThat(response.updatedAt()).isEqualTo(old.getUpdatedAt());
    assertNotFound(
        () -> asCustomer("customer2", () -> confirmations.confirm(old.getId(), "customer2")));
  }

  @Test
  void outerTransactionFailureRollsBackAFlushedConfirmation() {
    var held = liveHold(slot());
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                asCustomer(
                    "customer1",
                    () ->
                        transaction()
                            .execute(
                                status -> {
                                  confirmations.confirm(held.getId(), "customer1");
                                  assertThat(
                                          jdbc.queryForObject(
                                              "SELECT status FROM reservations WHERE id = ?",
                                              String.class,
                                              held.getId()))
                                      .isEqualTo("CONFIRMED");
                                  throw new IllegalStateException(
                                      "Injected outer transaction failure");
                                })));
    var stored = reservations.findById(held.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(stored.getUpdatedAt()).isEqualTo(held.getUpdatedAt());
  }

  @Test
  void concurrentConfirmationsReturnTheSameStateAndTimestamp() throws Exception {
    var held = liveHold(slot());
    var responses = new ConcurrentLinkedQueue<ReservationResponse>();
    Supplier<String> action =
        () ->
            asCustomer(
                "customer1",
                () -> {
                  var response = confirmations.confirm(held.getId(), "customer1");
                  responses.add(response);
                  return response.status().name();
                });
    assertThat(race(List.of(action, action, action)))
        .containsExactly("CONFIRMED", "CONFIRMED", "CONFIRMED");
    assertThat(responses).hasSize(3).containsOnly(responses.peek());
    assertThat(reservations.count()).isEqualTo(1);
    assertOneActive(held.getSlotId());
  }

  @Test
  void confirmationAndNewHoldSerializeForLiveOccupancy() throws Exception {
    var held = liveHold(slot());
    assertThat(
            race(
                List.of(
                    () ->
                        asCustomer(
                            "customer1",
                            () -> confirmations.confirm(held.getId(), "customer1").status().name()),
                    () ->
                        asCustomer(
                            "customer2",
                            () ->
                                holds.acquireHold(held.getSlotId(), "customer2").status().name()))))
        .containsExactlyInAnyOrder("CONFIRMED", "CONFLICT");
    assertThat(reservations.findById(held.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(reservations.count()).isEqualTo(1);
    assertOneActive(held.getSlotId());
  }

  @Test
  void overdueConfirmationRacingRebookingCannotReviveTheOldHold() throws Exception {
    var old = overdueHold(slot());
    assertThat(
            race(
                List.of(
                    () ->
                        asCustomer(
                            "customer1",
                            () -> confirmations.confirm(old.getId(), "customer1").status().name()),
                    () ->
                        asCustomer(
                            "customer2",
                            () ->
                                holds.acquireHold(old.getSlotId(), "customer2").status().name()))))
        .containsExactlyInAnyOrder("CONFLICT", "HELD");
    assertThat(reservations.findById(old.getId()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
    var replacement = reservations.findActiveBySlotId(old.getSlotId()).orElseThrow();
    assertThat(replacement.getId()).isNotEqualTo(old.getId());
    assertThat(replacement.getCustomerId()).isEqualTo(customer("customer2"));
    assertThat(reservations.count()).isEqualTo(2);
    assertOneActive(old.getSlotId());
  }

  @Test
  void confirmationsForDifferentSlotsSucceedIndependently() throws Exception {
    var first = liveHold(slot());
    var second = liveHold(slot());
    assertThat(
            race(
                List.of(
                    () ->
                        asCustomer(
                            "customer1",
                            () ->
                                confirmations.confirm(first.getId(), "customer1").status().name()),
                    () ->
                        asCustomer(
                            "customer1",
                            () ->
                                confirmations
                                    .confirm(second.getId(), "customer1")
                                    .status()
                                    .name()))))
        .containsExactly("CONFIRMED", "CONFIRMED");
  }

  @Test
  void waitingConfirmationRefreshesItsCachedDeadlineAndSamplesTimeAfterLocking() throws Exception {
    var slot = slot();
    var old = liveHold(slot);
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
                      "customer1",
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
                                    return confirmations.confirm(old.getId(), "customer1");
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
        assertThatThrownBy(() -> worker.get(15, TimeUnit.SECONDS))
            .isInstanceOfSatisfying(
                ExecutionException.class,
                e ->
                    assertThat(e.getCause())
                        .isInstanceOf(ReservationConflictException.class)
                        .hasMessage("Hold has expired"));
        var stored = reservations.findById(old.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(stored.getExpiresAt()).isEqualTo(cutoff);
        assertThat(stored.getUpdatedAt()).isEqualTo(old.getUpdatedAt());
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void waitingConfirmationReloadsAnAlreadyCommittedConfirmation() throws Exception {
    var slot = slot();
    var old = liveHold(slot);
    var pid = new AtomicInteger();
    var started = new CountDownLatch(1);
    var confirmedAt = databaseNow();
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      lock(blocker, slot.getId());
      var worker =
          executor.submit(
              () ->
                  asCustomer(
                      "customer1",
                      () ->
                          transaction()
                              .execute(
                                  status -> {
                                    reservations.findById(old.getId()).orElseThrow();
                                    pid.set(
                                        jdbc.queryForObject(
                                            "SELECT pg_backend_pid()", Integer.class));
                                    started.countDown();
                                    return confirmations.confirm(old.getId(), "customer1");
                                  })));
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBlocked(pid.get());
        try (var statement =
            blocker.prepareStatement(
                "UPDATE reservations SET status = 'CONFIRMED', updated_at = ? WHERE id = ?")) {
          statement.setObject(1, confirmedAt.atOffset(ZoneOffset.UTC));
          statement.setObject(2, old.getId());
          statement.executeUpdate();
        }
        blocker.commit();
        assertThat(worker.get(15, TimeUnit.SECONDS).updatedAt()).isEqualTo(confirmedAt);
        assertThat(reservations.findById(old.getId()).orElseThrow().getUpdatedAt())
            .isEqualTo(confirmedAt);
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void confirmationLockTimeoutRemainsAnInfrastructureFailure() throws Exception {
    var slot = slot();
    var held = liveHold(slot);
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
                                      return confirmations.confirm(held.getId(), "customer1");
                                    })));
        assertThatThrownBy(() -> worker.get(5, TimeUnit.SECONDS))
            .isInstanceOfSatisfying(
                ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOf(DataAccessException.class));
        assertThat(reservations.findById(held.getId()).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.HELD);
      } finally {
        blocker.rollback();
      }
    }
  }

  private List<String> race(List<Supplier<String>> actions) throws Exception {
    var pids = ConcurrentHashMap.<Integer>newKeySet();
    var barrier = new CyclicBarrier(actions.size());
    try (var executor = Executors.newFixedThreadPool(actions.size())) {
      var results = new ArrayList<Future<String>>();
      for (var action : actions) {
        results.add(
            executor.submit(
                () -> {
                  try {
                    return transaction()
                        .execute(
                            status -> {
                              pids.add(
                                  jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                              try {
                                barrier.await(5, TimeUnit.SECONDS);
                              } catch (Exception exception) {
                                throw new IllegalStateException(
                                    "Concurrent test coordination failed", exception);
                              }
                              return action.get();
                            });
                  } catch (ReservationConflictException expected) {
                    return "CONFLICT";
                  }
                }));
      }
      var outcomes = new ArrayList<String>();
      for (var result : results) outcomes.add(result.get(20, TimeUnit.SECONDS));
      assertThat(pids).hasSize(actions.size());
      return outcomes;
    }
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setTimeout(10);
    return transaction;
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
    var provider = providers.saveAndFlush(new Provider("Confirmation service fixture"));
    return slots.saveAndFlush(
        new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now));
  }

  private Reservation liveHold(AppointmentSlot slot) {
    var now = databaseNow();
    return reservations.saveAndFlush(
        Reservation.hold(
            slot.getId(),
            customer("customer1"),
            now.minusSeconds(60),
            now.plusSeconds(300),
            slot.getStartsAt()));
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

  private void assertNotFound(Supplier<?> action) {
    assertThatThrownBy(action::get)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(e.getReason()).isEqualTo("Reservation not found");
            });
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
