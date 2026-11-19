package dev.komeil.boka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.ReservationExpiryService;
import dev.komeil.boka.reservation.ReservationRepository;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationReadHttpIT extends ApiIntegrationTest {
  private static final String PATH = "/api/v1/reservations";
  private static final String OWNER = "customer1";

  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired ReservationExpiryService expiry;
  @Autowired DataSource database;

  @Test
  void ownerReadsTheCommittedReservationAndCanStillReplayCreation() throws Exception {
    var slotId = slot();
    var created = createHold(slotId);
    var id = reservationId(created);
    var held = read(id, OWNER);
    assertThat(held.statusCode()).isEqualTo(200);
    assertThat(held.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(json(held)).isEqualTo(json(created));
    assertThat(json(held).has("customerId")).isFalse();
    var uppercaseId = call("GET", PATH + "/" + id.toString().toUpperCase(Locale.ROOT), OWNER, null);
    assertThat(uppercaseId.statusCode()).isEqualTo(200);
    assertThat(json(uppercaseId)).isEqualTo(json(held));

    jdbc.update(
        """
        UPDATE reservations SET expires_at = created_at + interval '1 microsecond' WHERE id = ?
        """,
        id);
    var overdue = read(id, OWNER);
    assertThat(overdue.statusCode()).isEqualTo(200);
    assertThat(json(overdue).get("status").asString()).isEqualTo("HELD");
    assertThat(expiry.expireBatch()).isEqualTo(1);

    var expired = read(id, OWNER);
    assertThat(expired.statusCode()).isEqualTo(200);
    assertThat(json(expired).get("status").asString()).isEqualTo("EXPIRED");
    var replay = createHold(slotId);
    assertThat(replay.body()).isEqualTo(created.body());
  }

  @Test
  void anotherCustomersReservationAndAMissingReservationBothReturn404() throws Exception {
    var id = reservationId(createHold(slot()));
    var foreign = read(id, "customer2");
    var missing = read(UUID.randomUUID(), "customer2");
    assertProblem(foreign, 404);
    assertProblem(missing, 404);
    assertThat(json(foreign).get("detail").asString())
        .isEqualTo("Reservation not found")
        .isEqualTo(json(missing).get("detail").asString());
    assertThat(json(foreign).has("slotId")).isFalse();
  }

  @Test
  void rejectsAnonymousUsersAdministratorsAndMalformedIds() throws Exception {
    var id = reservationId(createHold(slot()));
    var anonymous = read(id, null);
    assertProblem(anonymous, 401);
    assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"boka\"");
    assertProblem(read(id, "admin"), 403);
    assertProblem(call("GET", PATH + "/not-a-uuid", OWNER, null), 400);
    assertProblem(call("GET", PATH + "/1-1-1-1-1", OWNER, null), 400);
  }

  @Test
  void readsCommittedStateWhileAnotherTransactionHoldsTheSlotAndUpdatesTheReservation()
      throws Exception {
    var slotId = slot();
    var id = reservationId(createHold(slotId));
    try (var connection = database.getConnection();
        var lock =
            connection.prepareStatement(
                "SELECT id FROM appointment_slots WHERE id = ? FOR UPDATE");
        var update =
            connection.prepareStatement(
                "UPDATE reservations SET status = 'CANCELLED', updated_at = clock_timestamp() WHERE id = ?")) {
      connection.setAutoCommit(false);
      try {
        lock.setObject(1, slotId);
        lock.executeQuery().close();
        update.setObject(1, id);
        assertThat(update.executeUpdate()).isEqualTo(1);

        var duringWrite = read(id, OWNER);
        assertThat(duringWrite.statusCode()).isEqualTo(200);
        assertThat(json(duringWrite).get("status").asString()).isEqualTo("HELD");
        connection.commit();
      } finally {
        connection.rollback();
      }
    }
    var afterCommit = read(id, OWNER);
    assertThat(afterCommit.statusCode()).isEqualTo(200);
    assertThat(json(afterCommit).get("status").asString()).isEqualTo("CANCELLED");
  }

  private UUID slot() {
    var now = reservations.currentDatabaseTime();
    var provider = providers.saveAndFlush(new Provider("Reservation read fixture"));
    return slots
        .saveAndFlush(
            new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now))
        .getId();
  }

  private HttpResponse<String> createHold(UUID slotId) throws Exception {
    var response =
        call("POST", PATH, OWNER, Map.of("slotId", slotId), "Idempotency-Key", "read-fixture");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return response;
  }

  private UUID reservationId(HttpResponse<String> response) {
    return UUID.fromString(json(response).get("id").asString());
  }

  private HttpResponse<String> read(UUID reservationId, String username) throws Exception {
    return call("GET", PATH + "/" + reservationId, username, null);
  }

  private void assertProblem(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(json(response).get("status").asInt()).isEqualTo(status);
  }
}
