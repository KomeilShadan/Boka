package dev.komeil.boka.provider;

import java.util.UUID;

public record ProviderResponse(UUID id, String displayName, boolean active) {
  static ProviderResponse from(Provider provider) {
    return new ProviderResponse(provider.getId(), provider.getDisplayName(), provider.isActive());
  }
}
