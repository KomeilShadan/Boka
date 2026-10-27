package dev.komeil.boka.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users")
public class AppUser {
  @Id private UUID id;
  private String username;
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  private Role role;

  private boolean enabled;
  private Instant createdAt;

  protected AppUser() {}

  public AppUser(String username, String passwordHash, Role role, Instant createdAt) {
    this.id = UUID.randomUUID();
    this.username = normalize(username);
    this.passwordHash = passwordHash;
    this.role = role;
    this.enabled = true;
    this.createdAt = createdAt;
  }

  public static String normalize(String username) {
    return username.strip().toLowerCase(Locale.ROOT);
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
