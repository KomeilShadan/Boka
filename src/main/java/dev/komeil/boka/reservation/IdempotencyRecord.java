package dev.komeil.boka.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
  @Id private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID actorId;

  @Column(nullable = false, updatable = false, length = 40)
  private String operation;

  @Column(nullable = false, updatable = false, length = 128)
  private String requestKey;

  @Column(nullable = false, updatable = false, length = 64)
  private String requestHash;

  private UUID reservationId;
  private Integer responseStatus;

  @Column(columnDefinition = "text")
  private String responseBody;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  protected IdempotencyRecord() {}

  boolean matches(String hash) {
    return requestHash.equals(hash);
  }

  void complete(UUID reservationId, String body) {
    if (responseBody != null)
      throw new IllegalStateException("Creation result is already recorded");
    this.reservationId = reservationId;
    this.responseStatus = 201;
    this.responseBody = body;
  }

  ReservationCreationResult result() {
    if (responseStatus == null || responseBody == null) {
      throw new IllegalStateException("Creation result is incomplete");
    }
    return new ReservationCreationResult(responseStatus, responseBody);
  }
}
