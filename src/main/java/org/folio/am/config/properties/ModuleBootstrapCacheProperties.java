package org.folio.am.config.properties;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "application.module-bootstrap-cache")
public class ModuleBootstrapCacheProperties {
  private boolean enabled = true;
  private Duration ttl = Duration.ofHours(1);
  private int maxSize = 1000;
}
