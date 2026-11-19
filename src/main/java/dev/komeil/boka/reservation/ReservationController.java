package dev.komeil.boka.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
  private static final String UUID_TEXT = "(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";

  private final ReservationCreationService creation;
  private final ReservationReadService reservations;

  public ReservationController(
      ReservationCreationService creation, ReservationReadService reservations) {
    this.creation = creation;
    this.reservations = reservations;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> create(
      @RequestHeader HttpHeaders headers,
      @Valid @RequestBody CreateHoldRequest request,
      Principal customer) {
    var keys = headers.get("Idempotency-Key");
    if (keys == null || keys.size() != 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Exactly one Idempotency-Key header is required");
    }

    var result =
        creation.create(UUID.fromString(request.slotId()), keys.getFirst(), customer.getName());
    return ResponseEntity.status(result.statusCode())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body().getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ReservationResponse read(
      @PathVariable @Pattern(regexp = UUID_TEXT) String id, Principal customer) {
    return reservations.read(UUID.fromString(id), customer.getName());
  }

  @ExceptionHandler(ReservationConflictException.class)
  ProblemDetail reservationConflict(ReservationConflictException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
  }

  record CreateHoldRequest(@NotNull @Pattern(regexp = UUID_TEXT) String slotId) {}
}
