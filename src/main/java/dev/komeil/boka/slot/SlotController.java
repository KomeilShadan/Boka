package dev.komeil.boka.slot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/providers/{providerId}/slots")
public class SlotController {
  private final SlotService service;

  public SlotController(SlotService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SlotResponse create(
      @PathVariable UUID providerId, @Valid @RequestBody CreateSlotRequest request) {
    return service.create(providerId, request);
  }

  @GetMapping
  public SlotPage browse(
      @PathVariable UUID providerId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.browse(providerId, from.toInstant(), to.toInstant(), page, size);
  }
}
