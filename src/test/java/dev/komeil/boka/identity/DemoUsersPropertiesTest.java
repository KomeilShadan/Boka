package dev.komeil.boka.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoUsersPropertiesTest {
  @Test
  void validatesUtf8ByteLengthOfEveryDemoPassword() {
    String asciiLimit = "a".repeat(72);
    String multiByteLimit = "é".repeat(36);
    String tooLong = multiByteLimit + "a";

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(
              validator.validate(new DemoUsersProperties(asciiLimit, multiByteLimit, asciiLimit)))
          .isEmpty();

      for (var invalid :
          List.of(
              new DemoUsersProperties(tooLong, asciiLimit, asciiLimit),
              new DemoUsersProperties(asciiLimit, tooLong, asciiLimit),
              new DemoUsersProperties(asciiLimit, asciiLimit, tooLong))) {
        assertThat(validator.validate(invalid))
            .singleElement()
            .satisfies(
                violation ->
                    assertThat(violation.getMessage())
                        .isEqualTo("Demo passwords must be at most 72 UTF-8 bytes"));
      }
    }
  }
}
