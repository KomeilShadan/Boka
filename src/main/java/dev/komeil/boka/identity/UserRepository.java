package dev.komeil.boka.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByUsername(String username);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO users (id, username, password_hash, role, enabled, created_at)
          VALUES (gen_random_uuid(), :username, :passwordHash, :role, true, :createdAt)
          ON CONFLICT (username) DO NOTHING
          """,
      nativeQuery = true)
  void insertIfUsernameMissing(
      @Param("username") String username,
      @Param("passwordHash") String passwordHash,
      @Param("role") String role,
      @Param("createdAt") Instant createdAt);
}
