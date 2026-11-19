package dev.komeil.boka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.ReservationRepository;
import dev.komeil.boka.reservation.ReservationStatus;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationCreationHttpIT extends ApiIntegrationTest {
  private static final String PATH = "/api/v1/reservations";
  private static final String KEY_HEADER = "Idempotency-Key";
  private static final String CUSTOMER = "customer1";

  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @Autowired DataSource database;

  @Test
  void createsAndReplaysTheStoredJsonAfterExpiry() throws Exception {
    var slotId = slot();
    var first = hold(CUSTOMER, slotId, "first-hold");
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.headers().firstValue("Content-Type")).hasValue("application/json");
    var body = json(first);
    assertThat(body.get("slotId").asString()).isEqualTo(slotId.toString());
    assertThat(body.get("status").asString()).isEqualTo("HELD");
    assertThat(body.has("customerId")).isFalse();
    var id = UUID.fromString(body.get("id").asString());
    var customerId =
        jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, CUSTOMER);
    assertThat(reservations.findById(id).orElseThrow().getCustomerId()).isEqualTo(customerId);
    assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency_records", String.class))
        .isEqualTo(first.body());

    jdbc.update(
        "UPDATE reservations SET status = 'EXPIRED', updated_at = clock_timestamp() WHERE id = ?",
        id);
    var replay =
        call(
            "POST",
            PATH,
            CUSTOMER,
            Map.of("slotId", slotId.toString().toUpperCase(Locale.ROOT)),
            KEY_HEADER,
            "first-hold");
    assertThat(replay.statusCode()).isEqualTo(first.statusCode());
    assertThat(replay.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(reservations.findById(id).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void authorizesOnlyAuthenticatedCustomers() throws Exception {
    var slotId = slot();
    var anonymous = hold(null, slotId, "anonymous-hold");
    assertProblem(anonymous, 401);
    assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"boka\"");
    assertProblem(hold("admin", slotId, "admin-hold"), 403);
    assertThat(reservations.count()).isZero();
    assertThat(idempotencyRecords()).isZero();
  }

  @Test
  void rejectsMissingMalformedAndRepeatedKeysAndInvalidBodies() throws Exception {
    var slotId = slot();
    var body = Map.of("slotId", slotId);
    assertProblem(call("POST", PATH, CUSTOMER, body), 400);
    for (String invalidKey : List.of("", "two keys", "key,key", "k".repeat(129))) {
      assertProblem(call("POST", PATH, CUSTOMER, body, KEY_HEADER, invalidKey), 400);
    }
    assertProblem(call("POST", PATH, CUSTOMER, body, KEY_HEADER, "same", KEY_HEADER, "same"), 400);
    for (String invalidBody :
        List.of("", "{", "{}", "{\"slotId\":null}", "{\"slotId\":\"invalid\"}")) {
      assertProblem(
          request(
              port,
              "POST",
              PATH,
              CUSTOMER,
              "integration-customer1-password",
              invalidBody,
              KEY_HEADER,
              "valid-key"),
          400);
    }
    var bytes =
        ByteBuffer.allocate(16)
            .putLong(slotId.getMostSignificantBits())
            .putLong(slotId.getLeastSignificantBits())
            .array();
    for (var encoder : List.of(Base64.getEncoder(), Base64.getUrlEncoder().withoutPadding())) {
      assertProblem(
          call(
              "POST",
              PATH,
              CUSTOMER,
              Map.of("slotId", encoder.encodeToString(bytes)),
              KEY_HEADER,
              "valid-key"),
          400);
    }
    assertThat(reservations.count()).isZero();
    assertThat(idempotencyRecords()).isZero();
  }

  @Test
  void mapsMissingSlotsAndConflictsAndScopesKeysByCustomer() throws Exception {
    var slotId = slot();
    var alternateSlotId = slot();
    assertProblem(hold(CUSTOMER, UUID.randomUUID(), "shared"), 404);
    assertThat(idempotencyRecords()).isZero();
    assertThat(hold(CUSTOMER, slotId, "shared").statusCode()).isEqualTo(201);
    assertProblem(hold(CUSTOMER, alternateSlotId, "shared"), 409);
    assertProblem(hold("customer2", slotId, "shared"), 409);
    assertThat(hold("customer2", alternateSlotId, "shared").statusCode()).isEqualTo(201);
    assertThat(reservations.count()).isEqualTo(2);
    assertThat(idempotencyRecords()).isEqualTo(2);
  }

  @Test
  void concurrentIdenticalRequestsReturnTheSameBodyAndCreateOneHold() throws Exception {
    var attempts = race(slot(), "concurrent", "concurrent");
    assertThat(attempts).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
    assertThat(attempts.getFirst().body()).isEqualTo(attempts.getLast().body());
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(idempotencyRecords()).isEqualTo(1);
  }

  @Test
  void concurrentDifferentKeysForOneSlotReturnCreatedAndConflict() throws Exception {
    var attempts = race(slot(), "first", "second");
    assertThat(attempts).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    assertThat(reservations.count()).isEqualTo(1);
    assertThat(idempotencyRecords()).isEqualTo(1);
  }

  @Test
  void slotLockTimeoutReturns503AndLeavesTheKeyAvailableForRetry() throws Exception {
    var slotId = slot();
    try (var connection = database.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT id FROM appointment_slots WHERE id = ? FOR UPDATE")) {
      connection.setAutoCommit(false);
      try {
        statement.setObject(1, slotId);
        statement.executeQuery().close();
        assertProblem(hold(CUSTOMER, slotId, "lock-timeout"), 503);
        assertThat(reservations.count()).isZero();
        assertThat(idempotencyRecords()).isZero();
      } finally {
        connection.rollback();
      }
    }
    assertThat(hold(CUSTOMER, slotId, "lock-timeout").statusCode()).isEqualTo(201);
  }

  private UUID slot() {
    var now = reservations.currentDatabaseTime();
    var provider = providers.saveAndFlush(new Provider("Hold HTTP fixture"));
    return slots
        .saveAndFlush(
            new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now))
        .getId();
  }

  private HttpResponse<String> hold(String username, UUID slotId, String key) throws Exception {
    return call("POST", PATH, username, Map.of("slotId", slotId), KEY_HEADER, key);
  }

  private List<HttpResponse<String>> race(UUID slotId, String firstKey, String secondKey)
      throws Exception {
    var ready = new CyclicBarrier(2);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () -> {
                ready.await(5, TimeUnit.SECONDS);
                return hold(CUSTOMER, slotId, firstKey);
              });
      var second =
          threads.submit(
              () -> {
                ready.await(5, TimeUnit.SECONDS);
                return hold(CUSTOMER, slotId, secondKey);
              });
      return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
    }
  }

  private int idempotencyRecords() {
    return jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class);
  }

  private void assertProblem(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(json(response).get("status").asInt()).isEqualTo(status);
    assertThat(response.body()).doesNotContain("org.postgresql", "stackTrace", "SELECT ");
  }
}
