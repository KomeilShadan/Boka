package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.provider.Provider;
import dev.komeil.boka.provider.ProviderRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused",
      "spring.datasource.url=jdbc:postgresql://localhost/unused"
    })
@Import(BokaApplicationIT.Database.class)
class BokaApplicationIT {
  @TestConfiguration(proxyBeanMethods = false)
  static class Database {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:18.6-bookworm");
    }
  }

  @Value("${local.server.port}")
  int port;

  @Value("${local.management.port}")
  int managementPort;

  @Autowired ProviderRepository providers;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migratedSchemaPersistsProviderAndServesItThroughHttp() throws Exception {
    providers.deleteAll();
    var provider = providers.saveAndFlush(new Provider("Fictional Provider"));
    assertThat(providers.findById(provider.getId())).isPresent();
    var response = get(port, "/api/v1/status");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"application\":\"boka\"", "\"providerCount\":1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isEqualTo(1);
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
    assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
    assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
