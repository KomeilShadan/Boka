package dev.komeil.boka;

import dev.komeil.boka.provider.ProviderRepository;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "boka.demo.admin-password=integration-admin-password",
      "boka.demo.customer1-password=integration-customer1-password",
      "boka.demo.customer2-password=integration-customer2-password",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused",
      "spring.datasource.url=jdbc:postgresql://localhost/unused",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
@Import(ApiIntegrationTest.Database.class)
@ActiveProfiles("demo")
abstract class ApiIntegrationTest {
  static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  @TestConfiguration(proxyBeanMethods = false)
  static class Database {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:18.6-bookworm");
    }

    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Value("${local.server.port}")
  int port;

  @Value("${local.management.port}")
  int managementPort;

  @Autowired ProviderRepository providers;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void cleanCatalog() {
    jdbc.update("DELETE FROM reservations");
    jdbc.update("DELETE FROM appointment_slots");
    providers.deleteAll();
  }

  HttpResponse<String> get(int port, String path) throws Exception {
    return get(port, path, null, null);
  }

  HttpResponse<String> get(int port, String path, String username, String password)
      throws Exception {
    return request(port, "GET", path, username, password, null);
  }

  HttpResponse<String> call(String method, String path, String username, Object body)
      throws Exception {
    return request(
        port,
        method,
        path,
        username,
        username == null ? null : "integration-" + username + "-password",
        body == null ? null : mapper.writeValueAsString(body));
  }

  HttpResponse<String> request(
      int port, String method, String path, String username, String password, String body)
      throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body));
      if (username != null) {
        request.header(
            "Authorization",
            "Basic "
                + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
      }
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }
}
