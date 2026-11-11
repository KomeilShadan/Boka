package dev.komeil.boka.reservation;

public record IdempotencyKey(String value) {
  public IdempotencyKey {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException(
          "Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores, colons or hyphens");
    }
  }
}
