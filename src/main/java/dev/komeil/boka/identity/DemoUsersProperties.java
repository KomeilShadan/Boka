package dev.komeil.boka.identity;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("boka.demo")
public record DemoUsersProperties(
    @NotBlank @Size(min = 12) String adminPassword,
    @NotBlank @Size(min = 12) String customer1Password,
    @NotBlank @Size(min = 12) String customer2Password) {

  @AssertTrue(message = "Demo passwords must be at most 72 UTF-8 bytes")
  public boolean isPasswordByteLengthValid() {
    return fitsBCrypt(adminPassword)
        && fitsBCrypt(customer1Password)
        && fitsBCrypt(customer2Password);
  }

  private static boolean fitsBCrypt(String password) {
    return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
  }
}
