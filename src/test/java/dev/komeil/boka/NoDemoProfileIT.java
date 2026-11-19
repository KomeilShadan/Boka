package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.identity.DemoUsersInitializer;
import dev.komeil.boka.identity.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "test", inheritProfiles = false)
class NoDemoProfileIT extends ApiIntegrationTest {
  @Autowired UserRepository users;
  @Autowired ApplicationContext context;
  @Autowired PasswordEncoder encoder;

  @Test
  void nonDemoProfileCreatesNoUsersAndLoadsNoSeeder() {
    assertThat(users.count()).isZero();
    assertThat(context.getBeansOfType(DemoUsersInitializer.class)).isEmpty();
  }

  @Test
  void validCustomerCannotAccessReservationsOutsideDemoAndLocalProfiles() throws Exception {
    var username = "customer1";
    var password = "integration-customer1-password";
    jdbc.update(
        """
        INSERT INTO users (id, username, password_hash, role, enabled, created_at)
        VALUES (?, ?, ?, 'CUSTOMER', true, clock_timestamp())
        """,
        UUID.randomUUID(),
        username,
        encoder.encode(password));
    try {
      assertThat(get(port, "/api/v1/status", username, password).statusCode()).isEqualTo(200);
      assertThat(
              call(
                      "POST",
                      "/api/v1/reservations",
                      username,
                      Map.of("slotId", UUID.randomUUID()),
                      "Idempotency-Key",
                      "profile-check")
                  .statusCode())
          .isEqualTo(403);
      assertThat(
              get(port, "/api/v1/reservations/" + UUID.randomUUID(), username, password)
                  .statusCode())
          .isEqualTo(403);
    } finally {
      jdbc.update("DELETE FROM users WHERE username = ?", username);
    }
  }
}
