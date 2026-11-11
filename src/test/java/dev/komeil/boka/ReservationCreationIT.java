package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.*;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReservationCreationIT extends ApiIntegrationTest {
  @Autowired ReservationCreationService creations;
  @Autowired ReservationHoldService holds;
  @Autowired ReservationConfirmationService confirmations;
  @Autowired ReservationRepository reservations;
  @Autowired IdempotencyRepository requests;
  @Autowired SlotRepository slots;
  @Autowired DataSource database;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void commitsAndReplaysTheExactCreatedStatusAndJsonBody() {
    var slot = slot();
    var first = create(slot.getId(), "create-1", "customer1");
    assertThat(first.statusCode()).isEqualTo(201);
    var original = mapper.readValue(first.body(), ReservationResponse.class);
    assertThat(original.status()).isEqualTo(ReservationStatus.HELD);
    assertThat(original.slotId()).isEqualTo(slot.getId());
    assertThat(create(slot.getId(), "create-1", "customer1")).isEqualTo(first);
    assertThat(requests.count()).isEqualTo(1);
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency_records", String.class))
        .isEqualTo(first.body());
    assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_records", String.class))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void replayPreservesOriginalResponseAfterConfirmationSlotStartAndProviderDeactivation() {
    var slot = slot();
    var first = create(slot.getId(), "stable", "customer1");
    var held = mapper.readValue(first.body(), ReservationResponse.class);
    asActor("customer1", "CUSTOMER", () -> confirmations.confirm(held.id(), "customer1"));
    jdbc.update(
        "UPDATE appointment_slots SET starts_at = clock_timestamp() - interval '1 hour' WHERE id = ?",
        slot.getId());
    jdbc.update("UPDATE providers SET active = false WHERE id = ?", slot.getProvider().getId());
    assertThat(create(slot.getId(), "stable", "customer1")).isEqualTo(first);
    assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(mapper.readValue(first.body(), ReservationResponse.class).status())
        .isEqualTo(ReservationStatus.HELD);
  }

  @Test
  void expiredReplayCannotExtendOrResurrectTheOldHoldAfterRebooking() {
    var slot = slot();
    var first = create(slot.getId(), "old", "customer1");
    var held = mapper.readValue(first.body(), ReservationResponse.class);
    jdbc.update(
        """
        UPDATE reservations SET created_at = clock_timestamp() - interval '10 minutes',
          expires_at = clock_timestamp() - interval '1 minute', updated_at = clock_timestamp()
        WHERE id = ?
        """,
        held.id());
    var replacement =
        asActor("customer2", "CUSTOMER", () -> holds.acquireHold(slot.getId(), "customer2"));
    assertThat(create(slot.getId(), "old", "customer1")).isEqualTo(first);
    assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
    assertThat(replacement.id()).isNotEqualTo(held.id());
    assertThat(reservations.findActiveBySlotId(slot.getId()).orElseThrow().getId())
        .isEqualTo(replacement.id());
    assertThat(reservations.count()).isEqualTo(2);
  }

  @Test
  void sameKeyWithDifferentPayloadConflictsWithoutMutatingEitherBooking() {
    var first = slot();
    var second = slot();
    create(first.getId(), "same-key", "customer1");
    assertThatThrownBy(() -> create(second.getId(), "same-key", "customer1"))
        .isInstanceOf(ReservationConflictException.class)
        .hasMessageContaining("different request");
    assertThat(reservations.findActiveBySlotId(second.getId())).isEmpty();
    assertThat(requests.count()).isEqualTo(1);
  }

  @Test
  void keysAreScopedToPersistedActorAndAreCaseSensitive() {
    var a = create(slot().getId(), "Shared", "customer1");
    var b = create(slot().getId(), "Shared", "customer2");
    var c = create(slot().getId(), "shared", "customer1");
    assertThat(Set.of(a.body(), b.body(), c.body())).hasSize(3);
    assertThat(requests.count()).isEqualTo(3);
    assertThat(reservations.count()).isEqualTo(3);
  }

  @Test
  void authorizationAndPersistedAccountChecksPrecedeReplay() {
    var slot = slot();
    var first = create(slot.getId(), "private", "customer1");
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> creations.create(slot.getId(), "private", "customer1"))
        .isInstanceOfAny(
            AuthenticationCredentialsNotFoundException.class, AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                asActor("admin", "ADMIN", () -> creations.create(slot.getId(), "private", "admin")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    "customer2",
                    "CUSTOMER",
                    () -> creations.create(slot.getId(), "private", "customer1")))
        .isInstanceOf(AccessDeniedException.class);
    for (var change : List.of("enabled = false", "enabled = true, role = 'ADMIN'")) {
      try {
        jdbc.update("UPDATE users SET " + change + " WHERE username = 'customer1'");
        assertThatThrownBy(() -> create(slot.getId(), "private", "customer1"))
            .isInstanceOf(AccessDeniedException.class);
      } finally {
        jdbc.update(
            "UPDATE users SET enabled = true, role = 'CUSTOMER' WHERE username = 'customer1'");
      }
    }
    assertThat(create(slot.getId(), "private", "customer1")).isEqualTo(first);
    assertThatThrownBy(() -> create(slot.getId(), "private", "customer2"))
        .isInstanceOf(ReservationConflictException.class);
    assertThat(requests.count()).isEqualTo(1);
  }

  @Test
  void rejectsMalformedInputBeforeClaimingAKey() {
    var slot = slot();
    for (var key : Arrays.asList(null, "", "bad key", "x".repeat(129))) {
      assertThatThrownBy(() -> create(slot.getId(), key, "customer1"))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
    assertThatThrownBy(() -> create(null, "valid", "customer1"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(requests.count()).isZero();
    assertThat(reservations.count()).isZero();
  }

  @Test
  void databaseRejectsMalformedKeysAndPartialResponsesOutsideJava() {
    var actor =
        jdbc.queryForObject("SELECT id FROM users WHERE username = 'customer1'", UUID.class);
    for (var key : List.of("bad key", "x".repeat(129))) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
          INSERT INTO idempotency_records(id, actor_id, operation, request_key, request_hash)
          VALUES (?, ?, 'reservation.create.v1', ?, ?)
          """,
                      UUID.randomUUID(),
                      actor,
                      key,
                      "a".repeat(64)))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO idempotency_records(id, actor_id, operation, request_key, request_hash, response_status, response_body)
        VALUES (?, ?, 'reservation.create.v1', 'partial', ?, 201, '{}')
        """,
                    UUID.randomUUID(),
                    actor,
                    "a".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(requests.count()).isZero();
  }

  @Test
  void businessFailureRollsBackItsClaimSoTheKeyCanBeRetried() {
    var slot = slot();
    var blocker =
        asActor("customer2", "CUSTOMER", () -> holds.acquireHold(slot.getId(), "customer2"));
    assertThatThrownBy(() -> create(slot.getId(), "retry", "customer1"))
        .isInstanceOf(ReservationConflictException.class);
    assertThat(requests.count()).isZero();
    jdbc.update("UPDATE reservations SET status = 'CANCELLED' WHERE id = ?", blocker.id());
    assertThat(create(slot.getId(), "retry", "customer1").statusCode()).isEqualTo(201);
    assertThat(requests.count()).isEqualTo(1);
    assertThatThrownBy(() -> create(UUID.randomUUID(), "missing", "customer1"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(requests.count()).isEqualTo(1);
  }

  @Test
  void failureWhileStoringResponseRollsBackHoldAndClaim() {
    jdbc.execute(
        """
        CREATE FUNCTION fail_creation_response() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'Injected response storage failure'; END $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER fail_creation_response BEFORE UPDATE ON idempotency_records
        FOR EACH ROW EXECUTE FUNCTION fail_creation_response()
        """);
    var slot = slot();
    try {
      assertThatThrownBy(() -> create(slot.getId(), "failure", "customer1"))
          .isInstanceOf(DataAccessException.class);
      assertThat(requests.count()).isZero();
      assertThat(reservations.count()).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER fail_creation_response ON idempotency_records");
      jdbc.execute("DROP FUNCTION fail_creation_response()");
    }
    assertThat(create(slot.getId(), "failure", "customer1").statusCode()).isEqualTo(201);
  }

  @Test
  void enclosingRollbackAfterFlushRemovesTheResponseAndHoldTogether() {
    var slot = slot();
    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          create(slot.getId(), "outer", "customer1");
                          assertThat(requests.count()).isEqualTo(1);
                          assertThat(reservations.count()).isEqualTo(1);
                          throw new IllegalStateException("Injected outer rollback");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests.count()).isZero();
    assertThat(reservations.count()).isZero();
    assertThat(create(slot.getId(), "outer", "customer1").statusCode()).isEqualTo(201);
  }

  @Test
  void concurrentIdenticalRequestsConvergeOnTheSameStatusBodyAndHold() throws Exception {
    var slot = slot();
    var results =
        race(List.of(slot.getId(), slot.getId(), slot.getId()), List.of("same", "same", "same"));
    assertThat(results).allMatch(result -> result instanceof ReservationCreationResult);
    assertThat(new HashSet<>(results)).hasSize(1);
    assertThat(requests.count()).isEqualTo(1);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void concurrentDifferentPayloadsForTheSameKeyProduceOneSuccessAndOneConflict() throws Exception {
    var results = race(List.of(slot().getId(), slot().getId()), List.of("same", "same"));
    assertThat(results.stream().filter(ReservationCreationResult.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(ReservationConflictException.class::isInstance)).hasSize(1);
    assertThat(requests.count()).isEqualTo(1);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void differentKeysForTheSameSlotStillProduceOnlyOneLiveHold() throws Exception {
    var slot = slot();
    var results = race(List.of(slot.getId(), slot.getId()), List.of("a", "b"));
    assertThat(results.stream().filter(ReservationCreationResult.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(ReservationConflictException.class::isInstance)).hasSize(1);
    assertThat(requests.count()).isEqualTo(1);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void differentKeysAndSlotsSucceedIndependently() throws Exception {
    var results = race(List.of(slot().getId(), slot().getId()), List.of("a", "b"));
    assertThat(results).allMatch(result -> result instanceof ReservationCreationResult);
    assertThat(requests.count()).isEqualTo(2);
    assertThat(reservations.count()).isEqualTo(2);
  }

  @Test
  void waitingClaimCanTakeOverAfterTheFirstTransactionRollsBack() throws Exception {
    var slot = slot();
    var pid = new AtomicInteger();
    var started = new CountDownLatch(1);
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      insertClaim(blocker, "takeover");
      var future =
          executor.submit(
              () ->
                  transaction()
                      .execute(
                          status -> {
                            pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            started.countDown();
                            return create(slot.getId(), "takeover", "customer1");
                          }));
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        awaitBlocked(pid.get());
        blocker.rollback();
        assertThat(future.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      } finally {
        blocker.rollback();
      }
    }
    assertThat(requests.count()).isEqualTo(1);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void waitingClaimTimeoutIsInfrastructureFailureAndDoesNotLeaveARecord() throws Exception {
    var slot = slot();
    try (var blocker = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      insertClaim(blocker, "blocked");
      try {
        var future =
            executor.submit(
                () ->
                    transaction()
                        .execute(
                            status -> {
                              jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                              return create(slot.getId(), "blocked", "customer1");
                            }));
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .isInstanceOfSatisfying(
                ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOf(DataAccessException.class));
      } finally {
        blocker.rollback();
      }
    }
    assertThat(requests.count()).isZero();
    assertThat(reservations.count()).isZero();
  }

  private List<Object> race(List<UUID> slotIds, List<String> keys) throws Exception {
    var pids = ConcurrentHashMap.<Integer>newKeySet();
    var barrier = new CyclicBarrier(slotIds.size());
    try (var executor = Executors.newFixedThreadPool(slotIds.size())) {
      var futures = new ArrayList<Future<Object>>();
      for (int i = 0; i < slotIds.size(); i++) {
        var id = slotIds.get(i);
        var key = keys.get(i);
        futures.add(
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
                              } catch (Exception e) {
                                throw new IllegalStateException(e);
                              }
                              return create(id, key, "customer1");
                            });
                  } catch (ReservationConflictException e) {
                    return e;
                  }
                }));
      }
      var results = new ArrayList<Object>();
      for (var f : futures) results.add(f.get(15, TimeUnit.SECONDS));
      assertThat(pids).hasSize(slotIds.size());
      return results;
    }
  }

  private void insertClaim(Connection connection, String key) throws Exception {
    try (var statement =
        connection.prepareStatement(
            """
        INSERT INTO idempotency_records(id, actor_id, operation, request_key, request_hash)
        VALUES (?, (SELECT id FROM users WHERE username='customer1'), 'reservation.create.v1', ?, ?)
        """)) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, key);
      statement.setString(3, "a".repeat(64));
      statement.executeUpdate();
    }
  }

  private void awaitBlocked(int pid) {
    var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid)))
        return;
      Thread.onSpinWait();
    }
    fail("Creation worker did not wait for the claim");
  }

  private AppointmentSlot slot() {
    var provider = providers.saveAndFlush(new Provider("Idempotency provider"));
    var now = reservations.currentDatabaseTime();
    return slots.saveAndFlush(
        new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now));
  }

  private ReservationCreationResult create(UUID slotId, String key, String username) {
    return asActor(username, "CUSTOMER", () -> creations.create(slotId, key, username));
  }

  private TransactionTemplate transaction() {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setTimeout(10);
    return transaction;
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
