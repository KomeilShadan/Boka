package dev.komeil.boka.provider;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProviderTest {
  @Test
  void createsActiveProviderWithNormalizedNameAndIdentity() {
    var provider = new Provider("  Demo Provider  ");
    assertThat(provider.getId()).isNotNull();
    assertThat(provider.getDisplayName()).isEqualTo("Demo Provider");
    assertThat(provider.isActive()).isTrue();
  }

  @Test
  void rejectsMissingAndOversizedNames() {
    for (String name : new String[] {null, "", "  ", "x".repeat(121)}) {
      assertThatIllegalArgumentException().isThrownBy(() -> new Provider(name));
    }
  }
}
