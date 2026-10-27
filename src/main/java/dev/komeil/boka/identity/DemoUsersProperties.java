package dev.komeil.boka.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("boka.demo")
public record DemoUsersProperties(
    @NotBlank @Size(min = 12, max = 72) String adminPassword,
    @NotBlank @Size(min = 12, max = 72) String customer1Password,
    @NotBlank @Size(min = 12, max = 72) String customer2Password) {}
