package dev.komeil.boka.slot;

import dev.komeil.boka.provider.Provider;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "appointment_slots")
public class AppointmentSlot {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "provider_id")
  private Provider provider;

  private Instant startsAt;
  private Instant endsAt;
  private Instant createdAt;

  protected AppointmentSlot() {}

  public AppointmentSlot(Provider provider, Instant startsAt, Instant endsAt, Instant now) {
    this.provider = Objects.requireNonNull(provider);
    this.startsAt = Objects.requireNonNull(startsAt).truncatedTo(ChronoUnit.MICROS);
    this.endsAt = Objects.requireNonNull(endsAt).truncatedTo(ChronoUnit.MICROS);
    if (!this.endsAt.isAfter(this.startsAt)) {
      throw new IllegalArgumentException(
          "Slot end must be after its start at microsecond precision");
    }
    if (!this.startsAt.isAfter(now)) {
      throw new IllegalArgumentException("Slot start must be in the future");
    }
    this.id = UUID.randomUUID();
    this.createdAt = now;
  }

  public UUID getId() {
    return id;
  }

  public Provider getProvider() {
    return provider;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public Instant getEndsAt() {
    return endsAt;
  }
}
