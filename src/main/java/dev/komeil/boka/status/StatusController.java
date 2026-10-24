package dev.komeil.boka.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
  private final StatusService service;

  public StatusController(StatusService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/status")
  public StatusResponse status() {
    return service.status();
  }
}
