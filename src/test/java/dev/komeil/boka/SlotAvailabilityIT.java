package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.ReservationConflictException;
import dev.komeil.boka.reservation.ReservationHoldService;
import dev.komeil.boka.reservation.ReservationRepository;
import dev.komeil.boka.reservation.ReservationStatus;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SlotAvailabilityIT extends ApiIntegrationTest {
  @Autowired SlotRepository slots;
  @Autowired ReservationRepository reservations;
  @Autowired ReservationHoldService holds;
  @Autowired DataSource database;
  @Autowired EntityManagerFactory entityManagerFactory;

  @Test
  void httpBrowseShowsOccupancyForAllReadersWithoutRevealingBookingDetails() throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    var free = slot(provider, now.plusSeconds(3600));
    var held = slot(provider, now.plusSeconds(7200));
    var overdue = slot(provider, now.plusSeconds(10800));
    var confirmed = slot(provider, now.plusSeconds(14400));
    var cancelled = slot(provider, now.plusSeconds(18000));
    var expired = slot(provider, now.plusSeconds(21600));
    var heldId = reserve(held, "HELD", now.plusSeconds(600));
    reserve(overdue, "HELD", now.minusSeconds(60));
    reserve(confirmed, "CONFIRMED", now.minusSeconds(60));
    reserve(cancelled, "CANCELLED", now.plusSeconds(600));
    reserve(expired, "EXPIRED", now.minusSeconds(60));

    for (var username : List.of("customer1", "customer2", "admin")) {
      var response = call("GET", window(provider, now), username, null);
      assertThat(response.statusCode()).isEqualTo(200);
      var body = json(response);
      assertThat(body.get("totalElements").asInt()).isEqualTo(6);
      var items = body.get("items");
      assertThat(items.size()).isEqualTo(6);
      var expected = List.of(true, false, true, false, true, true);
      var expectedSlots = List.of(free, held, overdue, confirmed, cancelled, expired);
      for (int i = 0; i < expected.size(); i++) {
        assertThat(items.get(i).get("id").asString())
            .isEqualTo(expectedSlots.get(i).getId().toString());
        assertThat(items.get(i).get("available").asBoolean()).isEqualTo(expected.get(i));
      }
      assertThat(response.body())
          .doesNotContain("customerId", "reservationId", "expiresAt", heldId.toString());
    }
    assertThat(reservations.count()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservations WHERE slot_id = ?", String.class, overdue.getId()))
        .isEqualTo("HELD");
  }

  @Test
  void queryUsesStrictExpiryAndStartBoundariesAtMicrosecondPrecision() {
    var provider = provider();
    var cutoff = Instant.parse("2040-01-01T00:00:00Z");
    var slot = slot(provider, cutoff.plusSeconds(3600));
    reserve(slot, "HELD", cutoff);
    var before = browse(provider, cutoff.minusNanos(1000));
    assertThat(before.getFirst().available()).isFalse();
    assertThat(browse(provider, cutoff).getFirst().available()).isTrue();
    assertThat(browse(provider, cutoff.plusNanos(1000)).getFirst().available()).isTrue();
    assertThat(browse(provider, slot.getStartsAt().minusNanos(1000))).hasSize(1);
    assertThat(browse(provider, slot.getStartsAt())).isEmpty();
    assertThat(browse(provider, slot.getStartsAt().plusNanos(1000))).isEmpty();
  }

  @Test
  void httpBrowseUsesDatabaseTimeRatherThanTheFixedApplicationClock() throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    assertThat(now).isBefore(NOW);
    var future = slot(provider, now.plusSeconds(3600));
    reserve(future, "HELD", now.plusSeconds(600));
    slot(provider, now.minusSeconds(1800));
    var response = call("GET", window(provider, now), "customer2", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("totalElements").asInt()).isEqualTo(1);
    assertThat(json(response).get("items").get(0).get("id").asString())
        .isEqualTo(future.getId().toString());
    assertThat(json(response).get("items").get(0).get("available").asBoolean()).isFalse();
  }

  @Test
  void pagingCountsSlotsOnceDespiteHistoryAndIncludesUnavailableSlots() throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    var first = slot(provider, now.plusSeconds(3600));
    var second = slot(provider, now.plusSeconds(7200));
    var third = slot(provider, now.plusSeconds(10800));
    for (int i = 0; i < 4; i++) reserve(first, "EXPIRED", now.minusSeconds(60 + i));
    reserve(first, "CANCELLED", now.plusSeconds(600));
    reserve(first, "HELD", now.plusSeconds(600));
    reserve(second, "CONFIRMED", now.minusSeconds(60));
    var other = slot(provider(), now.plusSeconds(3600));
    reserve(other, "HELD", now.plusSeconds(600));

    var page0 = json(call("GET", window(provider, now) + "&size=2", "customer1", null));
    assertThat(page0.get("totalElements").asInt()).isEqualTo(3);
    assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
    assertThat(page0.get("items").size()).isEqualTo(2);
    assertThat(page0.get("items").get(0).get("id").asString()).isEqualTo(first.getId().toString());
    assertThat(page0.get("items").get(0).get("available").asBoolean()).isFalse();
    assertThat(page0.get("items").get(1).get("id").asString()).isEqualTo(second.getId().toString());
    var page1 = json(call("GET", window(provider, now) + "&size=2&page=1", "customer1", null));
    assertThat(page1.get("items").size()).isEqualTo(1);
    assertThat(page1.get("items").get(0).get("id").asString()).isEqualTo(third.getId().toString());
    assertThat(page1.get("items").get(0).get("available").asBoolean()).isTrue();
  }

  @Test
  void availableSnapshotDoesNotGuaranteeTheLaterAcquisition() throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    var slot = slot(provider, now.plusSeconds(3600));
    assertAvailable(provider, now, true);
    var held = asCustomer("customer1", () -> holds.acquireHold(slot.getId(), "customer1"));
    assertAvailable(provider, now, false);
    assertThatThrownBy(
            () -> asCustomer("customer2", () -> holds.acquireHold(slot.getId(), "customer2")))
        .isInstanceOf(ReservationConflictException.class);
    assertThat(reservations.findById(held.id()).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
    assertThat(reservations.count()).isEqualTo(1);
  }

  @Test
  void overdueSnapshotNeedsNoCleanupButAcquisitionAtomicallyReplacesIt() throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    var slot = slot(provider, now.plusSeconds(3600));
    var overdue = reserve(slot, "HELD", now.minusSeconds(60));
    assertAvailable(provider, now, true);
    assertThat(reservations.findById(overdue).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.HELD);
    var replacement = asCustomer("customer2", () -> holds.acquireHold(slot.getId(), "customer2"));
    assertThat(reservations.findById(overdue).orElseThrow().getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
    assertThat(replacement.id()).isNotEqualTo(overdue);
    assertAvailable(provider, now, false);
  }

  @Test
  void browseDoesNotWaitForUncommittedReservationWriterAndSeesTheLaterCommit() throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    var slot = slot(provider, now.plusSeconds(3600));
    try (var writer = database.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      writer.setAutoCommit(false);
      try (var lock =
          writer.prepareStatement("SELECT id FROM appointment_slots WHERE id = ? FOR UPDATE")) {
        lock.setObject(1, slot.getId());
        lock.executeQuery().close();
      }
      try (var insert =
          writer.prepareStatement(
              """
          INSERT INTO reservations VALUES (?, ?, (SELECT id FROM users WHERE username = 'customer1'),
            'HELD', clock_timestamp() + interval '1 hour', clock_timestamp() - interval '1 minute', clock_timestamp())
          """)) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, slot.getId());
        insert.executeUpdate();
      }
      var response =
          executor
              .submit(() -> call("GET", window(provider, now), "customer2", null))
              .get(5, TimeUnit.SECONDS);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(json(response).get("items").get(0).get("available").asBoolean()).isTrue();
      writer.commit();
    }
    assertAvailable(provider, now, false);
  }

  @Test
  void fullPageWithReservationHistoryUsesBoundedQueriesAndLoadsNoBookingEntities()
      throws Exception {
    var provider = provider();
    var now = slots.currentDatabaseTime();
    for (int i = 1; i <= 10; i++) {
      var slot = slot(provider, now.plusSeconds(i * 3600));
      reserve(slot, "EXPIRED", now.minusSeconds(60));
      reserve(slot, "HELD", now.plusSeconds(600));
    }
    var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.clear();
    var response = call("GET", window(provider, now) + "&size=5", "customer2", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("items").size()).isEqualTo(5);
    assertThat(json(response).get("totalElements").asInt()).isEqualTo(10);
    assertThat(stats.getEntityLoadCount()).isEqualTo(2); // Authenticated user and provider only.
    assertThat(stats.getEntityFetchCount()).isZero();
    assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
  }

  private Provider provider() {
    return providers.saveAndFlush(new Provider("Availability provider"));
  }

  private AppointmentSlot slot(Provider provider, Instant startsAt) {
    return slots.saveAndFlush(
        new AppointmentSlot(
            provider, startsAt, startsAt.plusSeconds(1800), startsAt.minusSeconds(3600)));
  }

  private UUID reserve(AppointmentSlot slot, String status, Instant expiresAt) {
    var id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO reservations (id, slot_id, customer_id, status, expires_at, created_at, updated_at)
        VALUES (?, ?, (SELECT id FROM users WHERE username = 'customer1'), ?, ?, ?, ?)
        """,
        id,
        slot.getId(),
        status,
        offset(expiresAt),
        offset(expiresAt.minusSeconds(3600)),
        offset(expiresAt.minusSeconds(3600)));
    return id;
  }

  private OffsetDateTime offset(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private String window(Provider provider, Instant now) {
    return "/api/v1/providers/"
        + provider.getId()
        + "/slots?from="
        + now.minusSeconds(3600)
        + "&to="
        + now.plusSeconds(86400);
  }

  private List<dev.komeil.boka.slot.SlotResponse> browse(Provider provider, Instant cutoff) {
    return slots
        .browse(
            provider.getId(),
            cutoff.minusSeconds(86400),
            cutoff.plusSeconds(86400),
            cutoff,
            PageRequest.of(0, 20))
        .getContent();
  }

  private void assertAvailable(Provider provider, Instant now, boolean expected) throws Exception {
    var response = call("GET", window(provider, now), "customer2", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("items").get(0).get("available").asBoolean()).isEqualTo(expected);
  }

  private <T> T asCustomer(String username, Supplier<T> action) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            username, "unused", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
