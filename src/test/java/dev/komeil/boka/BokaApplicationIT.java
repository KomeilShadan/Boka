package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.identity.DemoUsersInitializer;
import dev.komeil.boka.identity.Role;
import dev.komeil.boka.identity.UserRepository;
import dev.komeil.boka.provider.Provider;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BokaApplicationIT extends ApiIntegrationTest {
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired DemoUsersInitializer initializer;
  @Autowired DataSource database;
  @Autowired PlatformTransactionManager transactionManager;

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
        .isEqualTo(6);
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
  void authenticationLookupTimeoutReturns503AndRecovers() throws Exception {
    try (var connection = database.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute("LOCK TABLE users IN ACCESS EXCLUSIVE MODE");
        var response = call("GET", "/api/v1/status", "admin", null);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type"))
            .hasValue("application/problem+json");
        assertThat(json(response).get("detail").asString())
            .isEqualTo("Authentication service is unavailable");
      } finally {
        connection.rollback();
      }
    }
    assertThat(call("GET", "/api/v1/status", "admin", null).statusCode()).isEqualTo(200);
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
  void demoSeedingPreservesUserInsertedByAConcurrentStartup() throws Exception {
    var existingHash = users.findByUsername("customer2").orElseThrow().getPasswordHash();
    jdbc.update("DELETE FROM users WHERE username = 'customer2'");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var competingStartup =
          new TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    users.insertIfUsernameMissing(
                        "customer2", existingHash, Role.CUSTOMER.name(), NOW);
                    int blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    var pending = executor.submit(() -> initializer.run(null));
                    awaitSeedBlockedBy(blocker);
                    return pending;
                  });
      competingStartup.get(10, TimeUnit.SECONDS);
      assertThat(users.count()).isEqualTo(3);
      assertThat(users.findByUsername("customer2").orElseThrow().getPasswordHash())
          .isEqualTo(existingHash);
    } finally {
      initializer.run(null);
    }
  }

  private void awaitSeedBlockedBy(int blocker) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              """
              SELECT EXISTS (
                SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
              )
              """,
              Boolean.class,
              blocker))) {
        return;
      }
      Thread.onSpinWait();
    }
    fail("Demo startup did not reach the PostgreSQL lock wait");
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
