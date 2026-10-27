package dev.komeil.boka.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProviderRequest(@NotBlank @Size(max = 120) String displayName) {
  public CreateProviderRequest {
    if (displayName != null) displayName = displayName.strip();
  }
}
