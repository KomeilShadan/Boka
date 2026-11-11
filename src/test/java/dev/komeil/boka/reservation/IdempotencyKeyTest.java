package dev.komeil.boka.reservation;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {
  @Test
  void acceptsBoundedAsciiKeysWithoutChangingTheirIdentity() {
    for (var value :
        List.of("550e8400-e29b-41d4-a716-446655440000", "A_b.c:9-", "a", "x".repeat(128))) {
      assertThat(new IdempotencyKey(value).value()).isEqualTo(value);
    }
    assertThat(new IdempotencyKey("Key")).isNotEqualTo(new IdempotencyKey("key"));
  }

  @Test
  void rejectsMissingOversizedWhitespaceAndNonAsciiKeys() {
    for (var value :
        Arrays.asList(
            null, "", " ", " key", "key ", "a\nb", "a\rb", "a\tb", "a/b", "é", "x".repeat(129))) {
      assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey(value));
    }
  }
}
