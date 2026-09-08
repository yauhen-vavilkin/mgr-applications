package org.folio.am.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.folio.am.config.properties.ModuleBootstrapCacheProperties;
import org.folio.am.domain.entity.ModuleBootstrapDataProjection;
import org.folio.am.domain.model.ModuleBootstrapCacheEntry;
import org.folio.am.domain.model.ModuleBootstrapData;
import org.folio.am.repository.ModuleBootstrapCacheVersionRepository;
import org.folio.am.repository.ModuleBootstrapRepository;
import org.folio.common.domain.model.ModuleDescriptor;
import org.springframework.stereotype.Component;

/** Application-scoped cache for bootstrap descriptors. Discovery locations are deliberately not cached here. */
@Component
@RequiredArgsConstructor
public class ModuleBootstrapCache {

  private final ModuleBootstrapRepository repository;
  private final ModuleBootstrapCacheVersionRepository versionRepository;
  private final ModuleBootstrapCacheProperties properties;
  private final ObjectMapper objectMapper;
  private final Map<String, CacheValue> entries = new ConcurrentHashMap<>();

  public Map<String, ModuleBootstrapCacheEntry> get(List<String> applicationIds) {
    if (!properties.isEnabled()) {
      return load(applicationIds);
    }
    var version = versionRepository.getVersion();
    var result = cachedEntries(applicationIds, version);
    var missing = applicationIds.stream().filter(id -> !result.containsKey(id)).toList();
    loadMissing(missing, version, result);
    return applicationIds.stream().collect(toMap(id -> id, result::get, (first, ignored) -> first,
      LinkedHashMap::new));
  }

  private Map<String, ModuleBootstrapCacheEntry> cachedEntries(List<String> ids, long version) {
    var result = new LinkedHashMap<String, ModuleBootstrapCacheEntry>();
    var now = System.nanoTime();
    ids.forEach(id -> {
      var value = entries.get(id);
      if (value != null && value.version() == version && now - value.lastAccess() <= properties.getTtl().toNanos()) {
        value.touch();
        result.put(id, value.entry());
      }
    });
    return result;
  }

  private void loadMissing(List<String> ids, long version, Map<String, ModuleBootstrapCacheEntry> result) {
    if (ids.isEmpty()) {
      return;
    }
    load(ids).forEach((id, entry) -> {
      entries.put(id, new CacheValue(version, entry));
      result.put(id, entry);
    });
    trim();
  }

  private Map<String, ModuleBootstrapCacheEntry> load(List<String> applicationIds) {
    var loaded = repository.findBackendDataByApplicationIds(applicationIds).stream()
      .collect(groupingBy(ModuleBootstrapDataProjection::getApplicationId, LinkedHashMap::new, toMap(
        ModuleBootstrapDataProjection::getModuleId, this::convert, (first, ignored) -> first, LinkedHashMap::new)));
    var result = new LinkedHashMap<String, ModuleBootstrapCacheEntry>();
    for (var applicationId : applicationIds) {
      var modules = new ArrayList<>(loaded.containsKey(applicationId)
        ? loaded.get(applicationId).values() : List.<ModuleBootstrapData>of());
      var providers = new LinkedHashMap<String, List<ModuleBootstrapData>>();
      modules.forEach(module -> {
        var provides = module.getDescriptor().getProvides();
        if (provides != null) {
          provides.forEach(provided -> providers.computeIfAbsent(provided.getId(), ignored -> new ArrayList<>())
            .add(module));
        }
      });
      result.put(applicationId, new ModuleBootstrapCacheEntry(List.copyOf(modules), Map.copyOf(providers)));
    }
    return result;
  }

  private ModuleBootstrapData convert(ModuleBootstrapDataProjection projection) {
    try {
      var descriptor = projection.getDescriptor() == null
        ? new ModuleDescriptor() : objectMapper.readValue(projection.getDescriptor(), ModuleDescriptor.class);
      return ModuleBootstrapData.builder()
        .moduleId(projection.getModuleId())
        .applicationId(projection.getApplicationId())
        .name(projection.getName())
        .version(projection.getVersion())
        .systemUserRequired(projection.getSystemUserRequired())
        .descriptor(descriptor)
        .build();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to load module bootstrap data", e);
    }
  }

  private void trim() {
    while (entries.size() > properties.getMaxSize()) {
      entries.entrySet().stream().min(Map.Entry.comparingByValue((first, second) ->
        Long.compare(first.lastAccess(), second.lastAccess()))).ifPresent(entry -> entries.remove(entry.getKey()));
    }
  }

  private static final class CacheValue {
    private final long version;
    private final ModuleBootstrapCacheEntry entry;
    private volatile long lastAccess;

    private CacheValue(long version, ModuleBootstrapCacheEntry entry) {
      this.version = version;
      this.entry = entry;
      touch();
    }

    private long version() {
      return version;
    }

    private ModuleBootstrapCacheEntry entry() {
      return entry;
    }

    private long lastAccess() {
      return lastAccess;
    }

    private void touch() {
      lastAccess = System.nanoTime();
    }
  }
}
