package dev.komeil.boka.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ApplicationConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
