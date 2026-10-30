package dev.komeil.boka.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {
  @Id private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID slotId;

  @Column(nullable = false, updatable = false)
  private UUID customerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReservationStatus status;

  @Column(nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Reservation() {}

  private Reservation(
      UUID slotId, UUID customerId, Instant now, Instant expiresAt, Instant slotStartsAt) {
    this.slotId = Objects.requireNonNull(slotId);
    this.customerId = Objects.requireNonNull(customerId);
    this.createdAt = micros(now);
    this.expiresAt = micros(expiresAt);
    if (!this.expiresAt.isAfter(this.createdAt)) {
      throw new IllegalArgumentException(
          "Hold deadline must be after creation at microsecond precision");
    }
    if (!this.createdAt.isBefore(micros(slotStartsAt))) {
      throw new ReservationConflictException("Slot has already started");
    }
    this.id = UUID.randomUUID();
    this.status = ReservationStatus.HELD;
    this.updatedAt = this.createdAt;
  }

  public static Reservation hold(
      UUID slotId, UUID customerId, Instant now, Instant expiresAt, Instant slotStartsAt) {
    return new Reservation(slotId, customerId, now, expiresAt, slotStartsAt);
  }

  // The application service must lock the slot first and supply fresh database time.
  public void confirm(Instant now, Instant slotStartsAt) {
    var confirmedAt = micros(now);
    var startsAt = micros(slotStartsAt);
    if (status == ReservationStatus.CONFIRMED) return;
    if (status != ReservationStatus.HELD) {
      throw new ReservationConflictException("Reservation cannot be confirmed from " + status);
    }
    if (confirmedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("Confirmation time cannot precede creation");
    }
    if (!confirmedAt.isBefore(expiresAt)) {
      throw new ReservationConflictException("Hold has expired");
    }
    if (!confirmedAt.isBefore(startsAt)) {
      throw new ReservationConflictException("Slot has already started");
    }
    status = ReservationStatus.CONFIRMED;
    updatedAt = confirmedAt;
  }

  public boolean expireIfDue(Instant now) {
    var evaluatedAt = micros(now);
    if (status != ReservationStatus.HELD || evaluatedAt.isBefore(expiresAt)) return false;
    status = ReservationStatus.EXPIRED;
    updatedAt = evaluatedAt;
    return true;
  }

  private static Instant micros(Instant instant) {
    return Objects.requireNonNull(instant).truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getSlotId() {
    return slotId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
