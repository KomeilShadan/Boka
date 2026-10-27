package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class ProviderSlotsIT extends ApiIntegrationTest {
  @Autowired SlotRepository slots;
  @Autowired EntityManagerFactory entityManagerFactory;

  @Test
  void adminCreatesProviderAndOffsetSlotAndCustomersBrowse() throws Exception {
    var provider =
        call("POST", "/api/v1/providers", "admin", Map.of("displayName", "  Fictional Clinic  "));
    assertThat(provider.statusCode()).isEqualTo(201);
    assertThat(json(provider).get("displayName").asString()).isEqualTo("Fictional Clinic");
    assertThat(json(provider).get("active").asBoolean()).isTrue();
    String id = json(provider).get("id").asString();
    var slot =
        call(
            "POST",
            path(id),
            "admin",
            Map.of("startsAt", "2030-01-02T13:00:00+03:30", "endsAt", "2030-01-02T13:30:00+03:30"));
    assertThat(slot.statusCode()).isEqualTo(201);
    assertThat(json(slot).get("startsAt").asString()).isEqualTo("2030-01-02T09:30:00Z");
    assertThat(json(slot).get("providerId").asString()).isEqualTo(id);
    for (String username : new String[] {"customer1", "customer2", "admin"}) {
      var browse =
          call("GET", window(id, "2030-01-02T00:00:00Z", "2030-01-03T00:00:00Z"), username, null);
      assertThat(browse.statusCode()).isEqualTo(200);
      assertThat(json(browse).get("totalElements").asInt()).isEqualTo(1);
      assertThat(json(browse).get("items").get(0).get("id").asString())
          .isEqualTo(json(slot).get("id").asString());
      assertThat(json(browse).get("items").get(0).get("available").asBoolean()).isTrue();
    }
  }

  @Test
  void onlyAdminsMayCreateProvidersOrSlots() throws Exception {
    String id = providerId();
    for (String username : new String[] {null, "customer1", "customer2"}) {
      int expected = username == null ? 401 : 403;
      assertProblem(
          call("POST", "/api/v1/providers", username, Map.of("displayName", "Denied")), expected);
      assertProblem(call("POST", path(id), username, slotBody(60, 120)), expected);
    }
    assertProblem(
        call("GET", window(id, "2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z"), null, null), 401);
    assertThat(slots.count()).isZero();
    assertThat(providers.count()).isEqualTo(1);
  }

  @Test
  void validatesProviderNamesAndSlotInputsWithoutLeakingInternals() throws Exception {
    for (Object body :
        List.of(Map.of(), Map.of("displayName", "   "), Map.of("displayName", "x".repeat(121)))) {
      assertProblem(call("POST", "/api/v1/providers", "admin", body), 400);
    }
    String id = providerId();
    for (Object body :
        List.of(
            Map.of(),
            Map.of("startsAt", "tomorrow", "endsAt", "later"),
            Map.of("startsAt", "2030-01-01T01:00:00", "endsAt", "2030-01-01T02:00:00"),
            slotBody(-60, 60),
            slotBody(60, 60),
            slotBody(120, 60))) {
      assertProblem(call("POST", path(id), "admin", body), 400);
    }
    assertProblem(request(port, "POST", path(id), "admin", "integration-admin-password", "{"), 400);
    assertThat(slots.count()).isZero();
  }

  @Test
  void rejectsUnknownAndInactiveProviders() throws Exception {
    String unknown = UUID.randomUUID().toString();
    assertProblem(call("POST", path(unknown), "admin", slotBody(60, 120)), 404);
    assertProblem(
        call(
            "GET",
            window(unknown, "2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z"),
            "customer1",
            null),
        404);
    assertProblem(call("POST", path("not-a-uuid"), "admin", slotBody(60, 120)), 400);
    String id = providerId();
    jdbc.update("UPDATE providers SET active = false WHERE id = ?", UUID.fromString(id));
    assertProblem(call("POST", path(id), "admin", slotBody(60, 120)), 404);
    assertProblem(
        call("GET", window(id, "2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z"), "customer1", null),
        404);
  }

  @Test
  void rejectsOverlapButAcceptsAdjacencyAndDifferentProviders() throws Exception {
    String id = providerId();
    assertThat(call("POST", path(id), "admin", slotBody(60, 120)).statusCode()).isEqualTo(201);
    var conflict = call("POST", path(id), "admin", slotBody(90, 150));
    assertProblem(conflict, 409);
    assertThat(conflict.body())
        .contains("overlaps")
        .doesNotContain("23P01", "constraint", "INSERT");
    assertThat(call("POST", path(id), "admin", slotBody(120, 180)).statusCode()).isEqualTo(201);
    assertThat(call("POST", path(providerId()), "admin", slotBody(60, 120)).statusCode())
        .isEqualTo(201);
    assertThat(slots.count()).isEqualTo(3);
  }

  @Test
  void databaseRejectsOverlapEvenWhenApplicationIsBypassed() throws Exception {
    String id = providerId();
    assertThat(call("POST", path(id), "admin", slotBody(60, 120)).statusCode()).isEqualTo(201);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO appointment_slots VALUES (gen_random_uuid(), ?, '2030-01-01T00:01:30Z', '2030-01-01T00:03:00Z', now())
        """,
                    UUID.fromString(id)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO appointment_slots VALUES (gen_random_uuid(), ?, '2030-01-01T00:03:00Z', '2030-01-01T00:02:00Z', now())
        """,
                    UUID.fromString(id)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(slots.count()).isEqualTo(1);
  }

  @Test
  void concurrentOverlappingHttpRequestsCreateExactlyOneSlot() throws Exception {
    String id = providerId();
    var results = race(id, id);
    assertThat(results).containsExactlyInAnyOrder(201, 409);
    assertThat(slots.count()).isEqualTo(1);
  }

  @Test
  void concurrentSlotsForDifferentProvidersBothSucceed() throws Exception {
    var results = race(providerId(), providerId());
    assertThat(results).containsExactlyInAnyOrder(201, 201);
    assertThat(slots.count()).isEqualTo(2);
  }

  @Test
  void browsingUsesHalfOpenStartWindowStablePagesAndExcludesStartedSlots() throws Exception {
    var provider = providers.saveAndFlush(new Provider("Paged provider"));
    String id = provider.getId().toString();
    var first =
        slots.saveAndFlush(
            new AppointmentSlot(provider, NOW.plusSeconds(60), NOW.plusSeconds(120), NOW));
    var second =
        slots.saveAndFlush(
            new AppointmentSlot(provider, NOW.plusSeconds(120), NOW.plusSeconds(180), NOW));
    slots.saveAndFlush(
        new AppointmentSlot(provider, NOW.plusSeconds(180), NOW.plusSeconds(240), NOW));
    jdbc.update(
        "INSERT INTO appointment_slots VALUES (gen_random_uuid(), ?, '2029-12-31T23:00:00Z', '2029-12-31T23:30:00Z', now())",
        provider.getId());
    String range = window(id, "2029-12-31T00:00:00Z", "2030-01-02T00:00:00Z");
    var page0 = call("GET", range + "&size=2", "customer1", null);
    assertThat(page0.statusCode()).isEqualTo(200);
    assertThat(json(page0).get("totalElements").asInt()).isEqualTo(3);
    assertThat(json(page0).get("totalPages").asInt()).isEqualTo(2);
    assertThat(json(page0).get("items").get(0).get("id").asString())
        .isEqualTo(first.getId().toString());
    assertThat(json(page0).get("items").get(1).get("id").asString())
        .isEqualTo(second.getId().toString());
    var page1 = call("GET", range + "&size=2&page=1", "customer1", null);
    assertThat(json(page1).get("items").size()).isEqualTo(1);
    var bounded =
        call(
            "GET",
            window(id, first.getStartsAt().toString(), second.getStartsAt().toString()),
            "customer1",
            null);
    assertThat(json(bounded).get("items").size()).isEqualTo(1);
    var empty = call("GET", range + "&page=20", "customer1", null);
    assertThat(json(empty).get("items").size()).isZero();
  }

  @Test
  void boundsBrowseInputsAndRequiresOffsets() throws Exception {
    String id = providerId();
    String range = window(id, "2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z");
    for (String suffix :
        new String[] {"&size=0", "&size=101", "&page=-1", "&page=10001", "&size=abc"}) {
      assertProblem(call("GET", range + suffix, "customer1", null), 400);
    }
    for (String invalid :
        new String[] {
          path(id),
          window(id, "2030-01-01T00:00:00", "2030-01-02T00:00:00Z"),
          window(id, "2030-01-02T00:00:00Z", "2030-01-01T00:00:00Z"),
          window(id, "2030-01-01T00:00:00Z", "2030-02-02T00:00:00Z")
        }) {
      assertProblem(call("GET", invalid, "customer1", null), 400);
    }
  }

  @Test
  void browsingDoesNotFetchOneProviderPerSlot() throws Exception {
    var provider = providers.saveAndFlush(new Provider("Many slots"));
    for (int i = 1; i <= 10; i++) {
      slots.saveAndFlush(
          new AppointmentSlot(
              provider, NOW.plusSeconds(i * 60), NOW.plusSeconds((i + 1) * 60), NOW));
    }
    var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.clear();
    var response =
        call(
            "GET",
            window(provider.getId().toString(), "2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z"),
            "customer1",
            null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("items").size()).isEqualTo(10);
    assertThat(stats.getEntityFetchCount()).isZero();
    assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
  }

  private List<Integer> race(String first, String second) throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var requests = new ArrayList<Future<Integer>>();
      for (String id : List.of(first, second)) {
        requests.add(
            executor.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  var response = call("POST", path(id), "admin", slotBody(60, 120));
                  if (response.statusCode() == 409) assertProblem(response, 409);
                  return response.statusCode();
                }));
      }
      return List.of(
          requests.get(0).get(25, TimeUnit.SECONDS), requests.get(1).get(25, TimeUnit.SECONDS));
    }
  }

  private String providerId() {
    return providers.saveAndFlush(new Provider("Fictional Provider")).getId().toString();
  }

  private String path(String id) {
    return "/api/v1/providers/" + id + "/slots";
  }

  private String window(String id, String from, String to) {
    return path(id) + "?from=" + from + "&to=" + to;
  }

  private Map<String, String> slotBody(long start, long end) {
    return Map.of(
        "startsAt", NOW.plusSeconds(start).toString(), "endsAt", NOW.plusSeconds(end).toString());
  }

  private void assertProblem(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(json(response).get("status").asInt()).isEqualTo(status);
    assertThat(response.body())
        .doesNotContain("stackTrace", "org.hibernate", "org.postgresql", "password_hash");
  }
}
