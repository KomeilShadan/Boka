package dev.komeil.boka;

import static org.assertj.core.api.Assertions.*;

import dev.komeil.boka.identity.DemoUsersInitializer;
import dev.komeil.boka.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused",
      "spring.datasource.url=jdbc:postgresql://localhost/unused"
    })
@Import(ApiIntegrationTest.Database.class)
@ActiveProfiles("test")
class NoDemoProfileIT {
  @Autowired UserRepository users;
  @Autowired ApplicationContext context;

  @Test
  void nonDemoProfileCreatesNoUsersAndLoadsNoSeeder() {
    assertThat(users.count()).isZero();
    assertThat(context.getBeansOfType(DemoUsersInitializer.class)).isEmpty();
  }
}
