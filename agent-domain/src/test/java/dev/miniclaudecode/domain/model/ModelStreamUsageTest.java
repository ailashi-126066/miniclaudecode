package dev.miniclaudecode.domain.model;

import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ModelStreamUsageTest {
  @Test
  void supportsLegacyUsageAndRejectsImpossibleCacheCounts() {
    Assertions.assertThat(new UsageReported(10L, 2L)).isEqualTo(new UsageReported(10L, 2L, 0L, 0L));
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> new UsageReported(10L, 2L, 8L, 3L))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("cache token counts");
  }
}
