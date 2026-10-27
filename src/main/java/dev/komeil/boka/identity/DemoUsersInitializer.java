package dev.komeil.boka.identity;

import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local", "demo"})
@EnableConfigurationProperties(DemoUsersProperties.class)
public class DemoUsersInitializer implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final DemoUsersProperties properties;
  private final Clock clock;

  public DemoUsersInitializer(
      UserRepository users, PasswordEncoder encoder, DemoUsersProperties properties, Clock clock) {
    this.users = users;
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    seed("admin", properties.adminPassword(), Role.ADMIN);
    seed("customer1", properties.customer1Password(), Role.CUSTOMER);
    seed("customer2", properties.customer2Password(), Role.CUSTOMER);
  }

  private void seed(String username, String password, Role role) {
    if (users.findByUsername(username).isEmpty()) {
      users.save(new AppUser(username, encoder.encode(password), role, clock.instant()));
    }
  }
}
