package dev.komeil.boka.provider;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {
  private final ProviderService service;

  public ProviderController(ProviderService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProviderResponse create(@Valid @RequestBody CreateProviderRequest request) {
    return service.create(request);
  }
}
