package org.folio.am.domain.model;

import java.util.List;
import java.util.Map;
import lombok.Value;

/** Cached backend module descriptors for one application. */
@Value
public class ModuleBootstrapCacheEntry {
  List<ModuleBootstrapData> modules;
  Map<String, List<ModuleBootstrapData>> providersByInterface;

  public static ModuleBootstrapCacheEntry empty() {
    return new ModuleBootstrapCacheEntry(List.of(), Map.of());
  }
}
