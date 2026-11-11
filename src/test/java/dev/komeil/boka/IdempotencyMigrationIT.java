package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class IdempotencyMigrationIT {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-bookworm");

  @Test
  void addsV5ToAnExistingV4BookingWithoutRewritingHistory() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target("4")
        .load()
        .migrate();
    var id = UUID.randomUUID();
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO providers VALUES ('" + id + "', 'Existing provider', true)");
      statement.executeUpdate(
          "INSERT INTO users VALUES ('"
              + id
              + "', 'existing', 'not-a-real-password-hash', 'CUSTOMER', true, now())");
      statement.executeUpdate(
          "INSERT INTO appointment_slots VALUES ('"
              + id
              + "', '"
              + id
              + "', '2030-01-01T12:00:00Z', '2030-01-01T12:30:00Z', now())");
      statement.executeUpdate(
          "INSERT INTO reservations VALUES ('"
              + id
              + "', '"
              + id
              + "', '"
              + id
              + "', 'CONFIRMED', '2026-01-01T00:05:00Z', '2026-01-01T00:00:00Z', '2026-01-01T00:01:00Z')");
    }
    var flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      try (var result = statement.executeQuery("SELECT id, status FROM reservations")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getObject(1, UUID.class)).isEqualTo(id);
        assertThat(result.getString(2)).isEqualTo("CONFIRMED");
        assertThat(result.next()).isFalse();
      }
      try (var result = statement.executeQuery("SELECT count(*) FROM idempotency_records")) {
        result.next();
        assertThat(result.getInt(1)).isZero();
      }
    }
  }
}
