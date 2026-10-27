package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.identity.DemoUsersInitializer;
import dev.komeil.boka.identity.UserRepository;
import dev.komeil.boka.provider.Provider;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class BokaApplicationIT extends ApiIntegrationTest {
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired DemoUsersInitializer initializer;

  @Test
  void migratedSchemaPersistsProviderAndServesItThroughHttp() throws Exception {
    providers.deleteAll();
    var provider = providers.saveAndFlush(new Provider("Fictional Provider"));
    assertThat(providers.findById(provider.getId())).isPresent();
    var response = get(port, "/api/v1/status", "admin", "integration-admin-password");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"application\":\"boka\"", "\"providerCount\":1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isEqualTo(3);
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO providers VALUES (gen_random_uuid(), '', true)"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    providers.deleteAll();
  }

  @Test
  void healthGroupsAreUpAndManagementIsSeparate() throws Exception {
    for (String group : new String[] {"liveness", "readiness"}) {
      var response = get(managementPort, "/actuator/health/" + group);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }
    assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(401);
    assertThat(
            get(managementPort, "/actuator/env", "admin", "integration-admin-password")
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void authenticationIsDatabaseBackedStatelessAndDefaultDeny() throws Exception {
    var anonymous = get(port, "/api/v1/status");
    assertThat(anonymous.statusCode()).isEqualTo(401);
    assertThat(anonymous.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"boka\"");
    assertThat(anonymous.body())
        .contains("\"status\":401")
        .doesNotContain("password", "stackTrace");
    for (String username : new String[] {"admin", "customer1", "customer2"}) {
      var response =
          get(
              port,
              "/api/v1/status",
              username.toUpperCase(Locale.ROOT),
              "integration-" + username + "-password");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }
    assertThat(get(port, "/api/v1/status", "admin", "wrong").statusCode()).isEqualTo(401);
    assertThat(get(port, "/api/v1/status", "missing", "wrong").statusCode()).isEqualTo(401);
    assertThat(get(port, "/api/v1/status").statusCode()).isEqualTo(401);
    assertThat(get(port, "/api/v1/not-allowed", "admin", "integration-admin-password").statusCode())
        .isEqualTo(403);
  }

  @Test
  void demoPasswordsAreHashedAndReseedingPreservesUsers() {
    assertThat(users.count()).isEqualTo(3);
    var hash = users.findByUsername("admin").orElseThrow().getPasswordHash();
    assertThat(hash).startsWith("$2a$").doesNotContain("integration-admin-password");
    assertThat(encoder.matches("integration-admin-password", hash)).isTrue();
    initializer.run(null);
    assertThat(users.count()).isEqualTo(3);
    assertThat(users.findByUsername("admin").orElseThrow().getPasswordHash()).isEqualTo(hash);
  }

  @Test
  void disabledUsersCannotAuthenticate() throws Exception {
    jdbc.update("UPDATE users SET enabled = false WHERE username = 'customer1'");
    try {
      assertThat(
              get(port, "/api/v1/status", "customer1", "integration-customer1-password")
                  .statusCode())
          .isEqualTo(401);
    } finally {
      jdbc.update("UPDATE users SET enabled = true WHERE username = 'customer1'");
    }
  }
}
