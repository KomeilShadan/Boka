package dev.komeil.boka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.reservation.ReservationRepository;
import dev.komeil.boka.slot.AppointmentSlot;
import dev.komeil.boka.slot.SlotRepository;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ReservationTransactionFailureIT extends ApiIntegrationTest {
  @Autowired ReservationRepository reservations;
  @Autowired SlotRepository slots;
  @MockitoSpyBean DataSource database;

  @Test
  void connectionFailureAtTransactionStartReturns503AndAllowsRetry() throws Exception {
    var now = reservations.currentDatabaseTime();
    var provider = providers.saveAndFlush(new Provider("Transaction failure fixture"));
    var slot =
        slots.saveAndFlush(
            new AppointmentSlot(provider, now.plusSeconds(3600), now.plusSeconds(5400), now));
    var rejectNext = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              var authentication = SecurityContextHolder.getContext().getAuthentication();
              if (authentication != null
                  && "customer1".equals(authentication.getName())
                  && rejectNext.compareAndSet(true, false)) {
                throw new SQLTransientConnectionException("Test connection acquisition failure");
              }
              return invocation.callRealMethod();
            })
        .when(database)
        .getConnection();

    var path = "/api/v1/reservations";
    var body = Map.of("slotId", slot.getId());
    var response = call("POST", path, "customer1", body, "Idempotency-Key", "start-failure");
    assertThat(rejectNext.get()).isFalse();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(json(response).get("detail").asString())
        .isEqualTo("Database operation could not be completed");
    assertThat(reservations.count()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class))
        .isZero();

    assertThat(
            call("POST", path, "customer1", body, "Idempotency-Key", "start-failure").statusCode())
        .isEqualTo(201);
    assertThat(reservations.count()).isEqualTo(1);
  }
}
