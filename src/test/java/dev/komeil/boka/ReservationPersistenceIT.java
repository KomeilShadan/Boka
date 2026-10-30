package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.Reservation;
import dev.komeil.boka.reservation.ReservationConflictException;
import dev.komeil.boka.reservation.ReservationRepository;
import dev.komeil.boka.reservation.ReservationStatus;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationPersistenceIT extends ApiIntegrationTest {
  private static final String INSERT =
      """
      INSERT INTO reservations(id, slot_id, customer_id, status, expires_at, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      """;

  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DataSource dataSource;

  @Test
  void persistsHoldAndConfirmsThroughManagedEntityDirtyChecking() {
    var slot = slot(3600);
    var hold = reservations.saveAndFlush(held(slot.getId(), customer("customer1")));
    var reloaded = reservations.findById(hold.getId()).orElseThrow();
    assertThat(reloaded.getSlotId()).isEqualTo(slot.getId());
    assertThat(reloaded.getCustomerId()).isEqualTo(customer("customer1"));
    assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(reloaded.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));

    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          var managed = reservations.findById(hold.getId()).orElseThrow();
          managed.confirm(NOW.plusSeconds(60), slot.getStartsAt());
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, hold.getId()))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                    "SELECT updated_at FROM reservations WHERE id = ?",
                    OffsetDateTime.class,
                    hold.getId())
                .toInstant())
        .isEqualTo(NOW.plusSeconds(60));
    assertThat(reservations.findById(hold.getId()).orElseThrow().getExpiresAt())
        .isEqualTo(NOW.plusSeconds(300));
  }

  @Test
  void rollbackAfterFlushedConfirmationRestoresTheStoredHold() {
    var slot = slot(3600);
    var hold = reservations.saveAndFlush(held(slot.getId(), customer("customer1")));
    var transaction = new TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      var managed = reservations.findById(hold.getId()).orElseThrow();
                      managed.confirm(NOW.plusSeconds(60), slot.getStartsAt());
                      reservations.flush();
                      assertThat(
                              jdbc.queryForObject(
                                  "SELECT status FROM reservations WHERE id = ?",
                                  String.class,
                                  hold.getId()))
                          .isEqualTo("CONFIRMED");
                      throw new IllegalStateException("Injected failure after flush");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Injected failure after flush");
    var stored = reservations.findById(hold.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ReservationStatus.HELD);
    assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void passedDeadlineStillOccupiesSlotUntilStatusChanges() {
    var slot = slot(3600);
    reservations.saveAndFlush(
        Reservation.hold(
            slot.getId(),
            customer("customer1"),
            NOW.minusSeconds(600),
            NOW.minusSeconds(300),
            slot.getStartsAt()));
    assertUniqueConflict(
        () -> insert(slot.getId(), customer("customer2"), "HELD", NOW, NOW.plusSeconds(300), NOW));
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void confirmedReservationStillOccupiesSlotAfterOriginalHoldDeadline() {
    var slot = slot(3600);
    var hold =
        Reservation.hold(
            slot.getId(),
            customer("customer1"),
            NOW.minusSeconds(600),
            NOW.minusSeconds(300),
            slot.getStartsAt());
    hold.confirm(NOW.minusSeconds(400), slot.getStartsAt());
    reservations.saveAndFlush(hold);
    assertUniqueConflict(
        () -> insert(slot.getId(), customer("customer2"), "HELD", NOW, NOW.plusSeconds(300), NOW));
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void historicalReservationsPermitOneNewActiveReservation() {
    var slot = slot(3600);
    for (String terminal : List.of("EXPIRED", "CANCELLED")) {
      insert(
          slot.getId(),
          customer("customer1"),
          terminal,
          NOW.minusSeconds(600),
          NOW.minusSeconds(300),
          NOW);
    }
    reservations.saveAndFlush(held(slot.getId(), customer("customer2")));
    assertThat(reservations.count()).isEqualTo(3);
    assertUniqueConflict(
        () ->
            insert(
                slot.getId(), customer("customer1"), "CONFIRMED", NOW, NOW.plusSeconds(300), NOW));
  }

  @Test
  void terminalReservationsCannotBeConfirmedOrResurrectedByTheDomain() {
    var slot = slot(3600);
    for (String terminal : List.of("EXPIRED", "CANCELLED")) {
      UUID id =
          insert(slot.getId(), customer("customer1"), terminal, NOW, NOW.plusSeconds(300), NOW);
      var stored = reservations.findById(id).orElseThrow();
      assertThatThrownBy(() -> stored.confirm(NOW.plusSeconds(60), slot.getStartsAt()))
          .isInstanceOf(ReservationConflictException.class);
      assertThat(stored.getStatus().name()).isEqualTo(terminal);
    }
  }

  @Test
  void reactivatingHistoricalRowCannotBypassActiveSlotUniqueness() {
    var slot = slot(3600);
    UUID old =
        insert(
            slot.getId(),
            customer("customer1"),
            "EXPIRED",
            NOW.minusSeconds(600),
            NOW.minusSeconds(300),
            NOW);
    reservations.saveAndFlush(held(slot.getId(), customer("customer2")));
    assertUniqueConflict(
        () -> jdbc.update("UPDATE reservations SET status = 'HELD' WHERE id = ?", old));
    assertThat(reservations.findById(old).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
  }

  @Test
  void databaseChecksReferencesStatusAndTimeBoundsForDirectWriters() {
    var slot = slot(3600);
    UUID customer = customer("customer1");
    assertThatThrownBy(
            () -> insert(UUID.randomUUID(), customer, "HELD", NOW, NOW.plusSeconds(300), NOW))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> insert(slot.getId(), UUID.randomUUID(), "HELD", NOW, NOW.plusSeconds(300), NOW))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> insert(slot.getId(), customer, "UNKNOWN", NOW, NOW.plusSeconds(300), NOW))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(slot.getId(), customer, "HELD", NOW, NOW, NOW))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                insert(
                    slot.getId(), customer, "HELD", NOW, NOW.plusSeconds(300), NOW.minusSeconds(1)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(reservations.count()).isZero();
  }

  @Test
  void twoIndependentDatabaseTransactionsCannotBothAcquireOneSlot() throws Exception {
    var slot = slot(3600);
    var attempts = race(slot.getId(), slot.getId());
    assertThat(attempts.stream().map(InsertAttempt::inserted).toList())
        .containsExactlyInAnyOrder(true, false);
    assertThat(attempts.stream().map(InsertAttempt::backendPid).distinct().count()).isEqualTo(2);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void independentTransactionsForDifferentSlotsBothSucceed() throws Exception {
    var attempts = race(slot(3600).getId(), slot(7200).getId());
    assertThat(attempts.stream().map(InsertAttempt::inserted).toList()).containsExactly(true, true);
    assertThat(attempts.stream().map(InsertAttempt::backendPid).distinct().count()).isEqualTo(2);
    assertThat(reservations.count()).isEqualTo(2);
  }

  @Test
  void reservationHttpRoutesStayDeniedUntilTheApplicationWorkflowExists() throws Exception {
    for (String username : List.of("admin", "customer1", "customer2")) {
      assertThat(
              call("POST", "/api/v1/reservations", username, Map.of("slotId", UUID.randomUUID()))
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              call(
                      "POST",
                      "/api/v1/reservations/" + UUID.randomUUID() + "/confirm",
                      username,
                      Map.of())
                  .statusCode())
          .isEqualTo(403);
    }
    assertThat(call("POST", "/api/v1/reservations", null, Map.of()).statusCode()).isEqualTo(401);
    assertThat(reservations.count()).isZero();
  }

  private record InsertAttempt(boolean inserted, int backendPid) {}

  private List<InsertAttempt> race(UUID first, UUID second) throws Exception {
    UUID firstCustomer = customer("customer1");
    UUID secondCustomer = customer("customer2");
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var one = executor.submit(() -> attempt(first, firstCustomer, barrier));
      var two = executor.submit(() -> attempt(second, secondCustomer, barrier));
      return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
    }
  }

  private InsertAttempt attempt(UUID slotId, UUID customerId, CyclicBarrier barrier)
      throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      int pid;
      try (var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT pg_backend_pid()")) {
        result.next();
        pid = result.getInt(1);
      }
      try {
        barrier.await(5, TimeUnit.SECONDS);
        try (var statement = connection.prepareStatement(INSERT)) {
          statement.setQueryTimeout(10);
          statement.setObject(1, UUID.randomUUID());
          statement.setObject(2, slotId);
          statement.setObject(3, customerId);
          statement.setString(4, "HELD");
          statement.setObject(5, offset(NOW.plusSeconds(300)));
          statement.setObject(6, offset(NOW));
          statement.setObject(7, offset(NOW));
          statement.executeUpdate();
        }
        connection.commit();
        return new InsertAttempt(true, pid);
      } catch (SQLException exception) {
        connection.rollback();
        assertThat(exception.getSQLState()).isEqualTo("23505");
        assertThat(exception.getMessage()).contains("reservations_one_active_per_slot");
        return new InsertAttempt(false, pid);
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private AppointmentSlot slot(long startsInSeconds) {
    var provider = providers.saveAndFlush(new Provider("Reservation fixture provider"));
    return slots.saveAndFlush(
        new AppointmentSlot(
            provider,
            NOW.plusSeconds(startsInSeconds),
            NOW.plusSeconds(startsInSeconds + 1800),
            NOW));
  }

  private UUID customer(String username) {
    return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
  }

  private Reservation held(UUID slotId, UUID customerId) {
    return Reservation.hold(slotId, customerId, NOW, NOW.plusSeconds(300), NOW.plusSeconds(3600));
  }

  private UUID insert(
      UUID slotId,
      UUID customerId,
      String status,
      Instant createdAt,
      Instant deadline,
      Instant updatedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        INSERT,
        id,
        slotId,
        customerId,
        status,
        offset(deadline),
        offset(createdAt),
        offset(updatedAt));
    return id;
  }

  private static OffsetDateTime offset(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private void assertUniqueConflict(Runnable action) {
    var failure = catchThrowable(action::run);
    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    var cause = ((DataIntegrityViolationException) failure).getMostSpecificCause();
    assertThat(cause).isInstanceOf(SQLException.class);
    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23505");
    assertThat(cause.getMessage()).contains("reservations_one_active_per_slot");
  }
}
