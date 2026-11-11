package dev.komeil.boka.reservation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {
  @Modifying
  @Query(
      value =
          """
          INSERT INTO idempotency_records (id, actor_id, operation, request_key, request_hash)
          VALUES (:id, :actorId, :operation, :key, :hash)
          ON CONFLICT (actor_id, operation, request_key) DO NOTHING
          """,
      nativeQuery = true)
  int claim(
      @Param("id") UUID id,
      @Param("actorId") UUID actorId,
      @Param("operation") String operation,
      @Param("key") String key,
      @Param("hash") String hash);

  Optional<IdempotencyRecord> findByActorIdAndOperationAndRequestKey(
      UUID actorId, String operation, String requestKey);
}
