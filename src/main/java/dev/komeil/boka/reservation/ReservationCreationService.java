package dev.komeil.boka.reservation;

import dev.komeil.boka.identity.Role;
import dev.komeil.boka.identity.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationCreationService {
  private static final String OPERATION = "reservation.create.v1";

  private final UserRepository users;
  private final IdempotencyRepository requests;
  private final ReservationHoldService holds;
  private final ObjectMapper mapper;

  public ReservationCreationService(
      UserRepository users,
      IdempotencyRepository requests,
      ReservationHoldService holds,
      ObjectMapper mapper) {
    this.users = users;
    this.requests = requests;
    this.holds = holds;
    this.mapper = mapper;
  }

  @PreAuthorize("hasRole('CUSTOMER') and #customerUsername == authentication.name")
  @Transactional(timeout = 10)
  public ReservationCreationResult create(UUID slotId, String key, String customerUsername) {
    var customer =
        users
            .findByUsername(customerUsername)
            .filter(user -> user.isEnabled() && user.getRole() == Role.CUSTOMER)
            .orElseThrow(() -> new AccessDeniedException("An enabled customer is required"));
    if (slotId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot ID is required");
    }
    IdempotencyKey requestKey;
    try {
      requestKey = new IdempotencyKey(key);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
    var hash = fingerprint(slotId);
    var inserted =
        requests.claim(UUID.randomUUID(), customer.getId(), OPERATION, requestKey.value(), hash);
    var record =
        requests
            .findByActorIdAndOperationAndRequestKey(customer.getId(), OPERATION, requestKey.value())
            .orElseThrow(() -> new IllegalStateException("Creation request was not recorded"));
    if (!record.matches(hash)) {
      throw new ReservationConflictException(
          "Idempotency-Key was already used for a different request");
    }
    if (inserted == 0) return record.result();

    var held = holds.acquireHold(slotId, customerUsername);
    record.complete(held.id(), mapper.writeValueAsString(held));
    requests.flush();
    return record.result();
  }

  private String fingerprint(UUID slotId) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(
              digest.digest((OPERATION + "\nslotId=" + slotId).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for request fingerprints", exception);
    }
  }
}
