package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.*;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReservationCancellationIT extends ApiIntegrationTest {
  @Autowired ReservationCancellationService cancellations;
  @Autowired ReservationConfirmationService confirmations;
  @Autowired ReservationHoldService holds;
  @Autowired ReservationCreationService creations;
  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DataSource database;

  @Test
  void cancellationReleasesSlotAndRetryDoesNotTouchTheReplacementOrCreationReplay()
      throws Exception {
    var slot = slot();
    var original =
        asActor(
            "customer1", "CUSTOMER", () -> creations.create(slot.getId(), "original", "customer1"));
    var held = mapper.readValue(original.body(), ReservationResponse.class);
    var before = reservations.currentDatabaseTime();
    var cancelled = cancel(held.id());
    assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
    assertThat(cancelled.updatedAt()).isBetween(before, reservations.currentDatabaseTime());
    assertThat(cancelled.createdAt()).isEqualTo(held.createdAt());
    assertThat(cancelled.expiresAt()).isEqualTo(held.expiresAt());
    assertThat(reservations.findActiveBySlotId(slot.getId())).isEmpty();
    var replacement =
        asActor("customer2", "CUSTOMER", () -> holds.acquireHold(slot.getId(), "customer2"));
    assertThat(replacement.id()).isNotEqualTo(held.id());
    assertThat(cancel(held.id())).isEqualTo(cancelled);
    assertThat(
            asActor(
                "customer1",
                "CUSTOMER",
                () -> creations.create(slot.getId(), "original", "customer1")))
        .isEqualTo(original);
    var active = reservations.findActiveBySlotId(slot.getId()).orElseThrow();
    assertThat(active.getId()).isEqualTo(replacement.id());
    assertThat(active.getUpdatedAt()).isEqualTo(replacement.updatedAt());
  }

  @Test
  void confirmedBookingCanCancelAfterTheOriginalHoldDeadline() {
    var slot = slot();
    var now = reservations.currentDatabaseTime();
    var reservation =
        Reservation.hold(
            slot.getId(),
            customer1(),
            now.minusSeconds(300),
            now.minusSeconds(60),
            slot.getStartsAt());
    reservation.confirm(now.minusSeconds(120), slot.getStartsAt());
    reservations.saveAndFlush(reservation);
    assertThat(cancel(reservation.getId()).status()).isEqualTo(ReservationStatus.CANCELLED);
    assertThat(reservations.findActiveBySlotId(slot.getId())).isEmpty();
  }

  @Test
  void nonOwnerAndUnknownIdReceiveTheSameNotFoundResponse() {
    var held = hold();
    for (var id : List.of(held.id(), UUID.randomUUID())) {
      assertThatThrownBy(
              () -> asActor("customer2", "CUSTOMER", () -> cancellations.cancel(id, "customer2")))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).isEqualTo("Reservation not found");
              });
    }
    assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void methodSecurityAndPersistedAccountChecksAlsoProtectCancellationRetries() {
    var held = hold();
    var cancelled = cancel(held.id());
    assertThatThrownBy(() -> cancellations.cancel(held.id(), "customer1"))
        .isInstanceOfAny(
            AuthenticationCredentialsNotFoundException.class, AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor("admin", "ADMIN", () -> cancellations.cancel(held.id(), "admin")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    "customer2", "CUSTOMER", () -> cancellations.cancel(held.id(), "customer1")))
        .isInstanceOf(AccessDeniedException.class);
    try {
      jdbc.update("UPDATE users SET enabled = false WHERE id = ?", customer1());
      assertThatThrownBy(() -> cancel(held.id())).isInstanceOf(AccessDeniedException.class);
      jdbc.update("UPDATE users SET enabled = true, role = 'ADMIN' WHERE id = ?", customer1());
      assertThatThrownBy(() -> cancel(held.id())).isInstanceOf(AccessDeniedException.class);
    } finally {
      jdbc.update("UPDATE users SET enabled = true, role = 'CUSTOMER' WHERE id = ?", customer1());
    }
    assertThat(cancel(held.id())).isEqualTo(cancelled);
  }

  @Test
  void overdueHoldIsRejectedAndLeftForExpiry() {
    var slot = slot();
    var now = reservations.currentDatabaseTime();
    var reservation =
        reservations.saveAndFlush(
            Reservation.hold(
                slot.getId(),
                customer1(),
                now.minusSeconds(300),
                now.minusSeconds(60),
                slot.getStartsAt()));
    assertThatThrownBy(() -> cancel(reservation.getId()))
        .isInstanceOf(ReservationConflictException.class)
        .hasMessage("Hold has expired");
    var stored = reservations.findById(reservation.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(stored.getUpdatedAt()).isEqualTo(reservation.getUpdatedAt());
  }

  @Test
  void outerRollbackRestoresTheActiveHoldAfterCancellationFlush() {
    var held = hold();
    var failure = new IllegalStateException("Injected outer rollback");
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          assertThat(cancel(held.id()).status())
                              .isEqualTo(ReservationStatus.CANCELLED);
                          assertThat(reservations.findActiveBySlotId(held.slotId())).isEmpty();
                          throw failure;
                        }))
        .isSameAs(failure);
    var active = reservations.findActiveBySlotId(held.slotId()).orElseThrow();
    assertThat(active.getId()).isEqualTo(held.id());
    assertThat(active.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(active.getUpdatedAt()).isEqualTo(held.updatedAt());
  }

  @Test
  void concurrentCancellationsReturnTheSameResult() throws Exception {
    var held = hold();
    var results = race(() -> cancel(held.id()), () -> cancel(held.id()));
    assertThat(results.getFirst())
        .isInstanceOfSatisfying(
            ReservationResponse.class,
            result -> assertThat(result.status()).isEqualTo(ReservationStatus.CANCELLED));
    assertThat(results.getLast()).isEqualTo(results.getFirst());
    assertThat(reservations.findActiveBySlotId(held.slotId())).isEmpty();
  }

  @Test
  void concurrentConfirmationAndCancellationLeaveOnlyCancelledHistory() throws Exception {
    var held = hold();
    var results =
        race(
            () -> cancel(held.id()),
            () ->
                asActor(
                    "customer1", "CUSTOMER", () -> confirmations.confirm(held.id(), "customer1")));
    assertThat(results.getFirst())
        .isInstanceOfSatisfying(
            ReservationResponse.class,
            result -> assertThat(result.status()).isEqualTo(ReservationStatus.CANCELLED));
    var confirmation = results.getLast();
    if (confirmation instanceof ReservationResponse result) {
      assertThat(result.status()).isEqualTo(ReservationStatus.CONFIRMED);
    } else {
      assertThat(confirmation).isEqualTo("Reservation cannot be confirmed from CANCELLED");
    }
    assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.CANCELLED);
    assertThat(reservations.findActiveBySlotId(held.slotId())).isEmpty();
  }

  @Test
  void cancellationAfterARealSlotLockWaitUsesRefreshedStateAndCurrentTime() throws Exception {
    var held = hold();
    var pid = new AtomicInteger();
    var started = new CountDownLatch(1);
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement("SELECT id FROM appointment_slots WHERE id = ? FOR UPDATE")) {
        statement.setObject(1, held.slotId());
        statement.executeQuery().close();
      }
      try {
        var worker =
            executor.submit(
                () ->
                    transaction()
                        .execute(
                            status -> {
                              reservations.findById(held.id()).orElseThrow();
                              pid.set(
                                  jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                              started.countDown();
                              return cancel(held.id());
                            }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBlocked(pid.get());
        OffsetDateTime cutoff;
        try (var statement = blocker.createStatement();
            var result = statement.executeQuery("SELECT clock_timestamp()")) {
          assertThat(result.next()).isTrue();
          cutoff = result.getObject(1, OffsetDateTime.class);
        }
        try (var statement =
            blocker.prepareStatement("UPDATE reservations SET expires_at = ? WHERE id = ?")) {
          statement.setObject(1, cutoff);
          statement.setObject(2, held.id());
          statement.executeUpdate();
        }
        blocker.commit();
        assertThatThrownBy(() -> worker.get(15, TimeUnit.SECONDS))
            .isInstanceOfSatisfying(
                ExecutionException.class,
                exception ->
                    assertThat(exception.getCause())
                        .isInstanceOf(ReservationConflictException.class)
                        .hasMessage("Hold has expired"));
        assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.HELD);
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void cancellationRouteRemainsDenied() throws Exception {
    var held = hold();
    assertThat(
            call("POST", "/api/v1/reservations/" + held.id() + "/cancel", "customer1", null)
                .statusCode())
        .isEqualTo(403);
    assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
  }

  private List<Object> race(Supplier<ReservationResponse> left, Supplier<ReservationResponse> right)
      throws Exception {
    var pids = ConcurrentHashMap.<Integer>newKeySet();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(left, right).stream()
              .map(
                  action ->
                      executor.<Object>submit(
                          () -> {
                            try {
                              return transaction()
                                  .execute(
                                      status -> {
                                        pids.add(
                                            jdbc.queryForObject(
                                                "SELECT pg_backend_pid()", Integer.class));
                                        try {
                                          barrier.await(5, TimeUnit.SECONDS);
                                        } catch (InterruptedException exception) {
                                          Thread.currentThread().interrupt();
                                          throw new IllegalStateException(
                                              "Concurrent test interrupted", exception);
                                        } catch (BrokenBarrierException
                                            | TimeoutException exception) {
                                          throw new IllegalStateException(
                                              "Concurrent test coordination failed", exception);
                                        }
                                        return action.get();
                                      });
                            } catch (ReservationConflictException exception) {
                              return exception.getMessage();
                            }
                          }))
              .toList();
      var results =
          List.of(
              futures.getFirst().get(20, TimeUnit.SECONDS),
              futures.getLast().get(20, TimeUnit.SECONDS));
      assertThat(pids).hasSize(2);
      return results;
    }
  }

  private void awaitBlocked(int pid) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid)))
        return;
      Thread.onSpinWait();
    }
    fail("Cancellation did not reach the PostgreSQL lock wait");
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setTimeout(10);
    return transaction;
  }

  private AppointmentSlot slot() {
    var now = reservations.currentDatabaseTime();
    var provider = providers.saveAndFlush(new Provider("Cancellation fixture"));
    return slots.saveAndFlush(
        new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now));
  }

  private ReservationResponse hold() {
    var slot = slot();
    return asActor("customer1", "CUSTOMER", () -> holds.acquireHold(slot.getId(), "customer1"));
  }

  private ReservationResponse cancel(UUID id) {
    return asActor("customer1", "CUSTOMER", () -> cancellations.cancel(id, "customer1"));
  }

  private UUID customer1() {
    return jdbc.queryForObject("SELECT id FROM users WHERE username = 'customer1'", UUID.class);
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
