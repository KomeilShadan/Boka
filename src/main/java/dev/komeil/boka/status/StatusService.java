package dev.komeil.boka.status;

import dev.komeil.boka.provider.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatusService {
  private final ProviderRepository providers;

  public StatusService(ProviderRepository providers) {
    this.providers = providers;
  }

  @Transactional(readOnly = true)
  public StatusResponse status() {
    return new StatusResponse("boka", providers.count());
  }
}
