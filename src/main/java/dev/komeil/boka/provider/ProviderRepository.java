package dev.komeil.boka.provider;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Provider p where p.id = :id")
  Optional<Provider> lockById(@Param("id") UUID id);
}
