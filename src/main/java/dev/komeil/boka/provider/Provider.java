package dev.komeil.boka.provider;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "providers")
public class Provider {
  @Id private UUID id;
  private String displayName;
  private boolean active;

  protected Provider() {}

  public Provider(String displayName) {
    if (displayName == null || displayName.isBlank() || displayName.strip().length() > 120) {
      throw new IllegalArgumentException("Display name must contain 1 to 120 characters");
    }
    this.id = UUID.randomUUID();
    this.displayName = displayName.strip();
    this.active = true;
  }

  public UUID getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public boolean isActive() {
    return active;
  }
}
