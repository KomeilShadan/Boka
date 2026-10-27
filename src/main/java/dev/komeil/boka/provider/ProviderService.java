package dev.komeil.boka.provider;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderService {
  private final ProviderRepository providers;

  public ProviderService(ProviderRepository providers) {
    this.providers = providers;
  }

  @Transactional
  public ProviderResponse create(CreateProviderRequest request) {
    return ProviderResponse.from(providers.save(new Provider(request.displayName())));
  }
}
