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
class ReservationMigrationIT {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-bookworm");

  @Test
  void upgradesExistingV3DataWithoutReplacingMigrations() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target("3")
        .load()
        .migrate();
    UUID providerId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      try (var provider =
          connection.prepareStatement(
              "INSERT INTO providers VALUES (?, 'Existing provider', true)")) {
        provider.setObject(1, providerId);
        provider.executeUpdate();
      }
      try (var slot =
          connection.prepareStatement(
              "INSERT INTO appointment_slots VALUES (?, ?, '2030-01-01T12:00:00Z', '2030-01-01T12:30:00Z', now())")) {
        slot.setObject(1, slotId);
        slot.setObject(2, providerId);
        slot.executeUpdate();
      }
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
      try (var result =
          statement.executeQuery(
              "SELECT count(*) FROM appointment_slots s JOIN providers p ON p.id = s.provider_id WHERE p.display_name = 'Existing provider'")) {
        result.next();
        assertThat(result.getInt(1)).isEqualTo(1);
      }
      try (var result = statement.executeQuery("SELECT count(*) FROM reservations")) {
        result.next();
        assertThat(result.getInt(1)).isZero();
      }
    }
  }
}
