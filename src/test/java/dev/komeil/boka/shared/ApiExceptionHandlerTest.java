package dev.komeil.boka.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

class ApiExceptionHandlerTest {
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void mapsSqlStateConnectionErrorsAtTransactionStartTo503() {
    var failure =
        new CannotCreateTransactionException(
            "Could not start transaction",
            new IllegalStateException(new SQLException("Connection lost", "08006")));

    assertThat(handler.transactionStartFailure(failure).getStatus()).isEqualTo(503);
  }

  @Test
  void keepsOtherTransactionStartFailuresAt500() {
    var failure =
        new CannotCreateTransactionException(
            "Could not start transaction", new SQLException("Invalid setup query", "42601"));

    assertThat(handler.transactionStartFailure(failure).getStatus()).isEqualTo(500);
  }
}
