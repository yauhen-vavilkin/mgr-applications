package org.folio.am.service;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static org.folio.am.utils.ModuleIdUtils.getNameAndVersion;
import static org.folio.common.utils.CollectionUtils.toStream;

import jakarta.persistence.EntityNotFoundException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.extern.log4j.Log4j2;
import org.folio.am.config.properties.ModuleBootstrapCacheProperties;
import org.folio.am.domain.dto.EgressBootstrap;
import org.folio.am.domain.dto.ModuleBootstrap;
import org.folio.am.domain.dto.ModuleBootstrapDiscovery;
import org.folio.am.domain.dto.ModuleBootstrapInterface;
import org.folio.am.domain.entity.ModuleBootstrapView;
import org.folio.am.domain.entity.ModuleLocationProjection;
import org.folio.am.domain.model.ModuleBootstrapCacheEntry;
import org.folio.am.domain.model.ModuleBootstrapData;
import org.folio.am.mapper.ModuleBootstrapMapper;
import org.folio.am.repository.ModuleBootstrapRepository;
import org.folio.common.domain.model.InterfaceDescriptor;
import org.folio.common.domain.model.InterfaceReference;
import org.folio.common.domain.model.ModuleDescriptor;
import org.folio.common.domain.model.RoutingEntry;
import org.folio.common.utils.InterfaceComparisonUtils;
import org.semver4j.Semver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Log4j2
@Service
public class ModuleBootstrapService {

  private final ModuleBootstrapRepository repository;
  private final ModuleBootstrapMapper mapper;
  private final ModuleBootstrapCache cache;
  private final ModuleBootstrapCacheProperties cacheProperties;

  /** Compatibility constructor used by the focused unit tests and for the uncached implementation. */
  public ModuleBootstrapService(ModuleBootstrapRepository repository, ModuleBootstrapMapper mapper) {
    this(repository, mapper, null, new ModuleBootstrapCacheProperties());
    this.cacheProperties.setEnabled(false);
  }

  @Autowired
  public ModuleBootstrapService(ModuleBootstrapRepository repository, ModuleBootstrapMapper mapper,
    ModuleBootstrapCache cache, ModuleBootstrapCacheProperties cacheProperties) {
    this.repository = repository;
    this.mapper = mapper;
    this.cache = cache;
    this.cacheProperties = cacheProperties;
  }

  @Transactional(readOnly = true)
  public ModuleBootstrap getById(String moduleId) {
    var views = repository.findAllRequiredByModuleId(moduleId);
    var moduleView = removeModuleViewById(moduleId, views);
    return new ModuleBootstrap().module(mapper.convert(moduleView)).requiredModules(resolveLegacy(moduleView, views));
  }

  @Transactional(readOnly = true)
  public ModuleBootstrap getIngressBootstrap(String moduleId) {
    var views = repository.findViewsById(moduleId);
    if (views.isEmpty()) {
      throw new EntityNotFoundException("Module not found by id: " + moduleId);
    }
    return new ModuleBootstrap().module(mapper.convert(views.get(0))).requiredModules(List.of());
  }

  @Transactional(readOnly = true)
  public EgressBootstrap getEgressBootstrap(String moduleId, List<String> applicationIds) {
    validateApplicationIds(applicationIds);
    return cacheProperties.isEnabled()
      ? getCachedEgressBootstrap(moduleId, applicationIds)
      : getLegacyEgressBootstrap(moduleId, applicationIds);
  }

  private EgressBootstrap getCachedEgressBootstrap(String moduleId, List<String> applicationIds) {
    var entries = cache.get(applicationIds);
    var module = findScopedModule(moduleId, entries);
    var requiredInterfaces = getRequiredOptionalInterfaces(module.data().getDescriptor());
    if (requiredInterfaces.isEmpty()) {
      return new EgressBootstrap().requiredModules(List.<ModuleBootstrapDiscovery>of());
    }
    var candidates = findCandidates(moduleId, applicationIds, entries, requiredInterfaces);
    candidates.remove(moduleId);
    var locations = findLocations(candidates);
    removeUnavailableCandidates(candidates, locations);
    return buildCachedResult(candidates, locations, requiredInterfaces);
  }

  private ScopedData findScopedModule(String moduleId, Map<String, ModuleBootstrapCacheEntry> entries) {
    return entries.entrySet().stream().flatMap(entry -> entry.getValue().getModules().stream()
        .filter(data -> moduleId.equals(data.getModuleId())).map(data -> new ScopedData(data, entry.getKey())))
      .findFirst().orElseThrow(() -> new EntityNotFoundException("Module not found by id: " + moduleId));
  }

  private Map<String, ScopedData> findCandidates(String moduleId, List<String> applicationIds,
    Map<String, ModuleBootstrapCacheEntry> entries, List<String> requiredInterfaces) {
    var candidates = new LinkedHashMap<String, ScopedData>();
    applicationIds.stream().sorted().forEach(applicationId -> {
      var entry = entries.get(applicationId);
      if (entry != null) {
        requiredInterfaces.forEach(interfaceId -> entry.getProvidersByInterface().getOrDefault(interfaceId, List.of())
          .forEach(provider -> candidates.putIfAbsent(provider.getModuleId(),
            new ScopedData(provider, applicationId))));
      }
    });
    return candidates;
  }

  private Map<String, String> findLocations(Map<String, ScopedData> candidates) {
    if (candidates.isEmpty()) {
      return Map.of();
    }
    return repository.findLocationsByModuleIds(candidates.keySet().stream().toList()).stream()
      .filter(location -> location.getLocation() != null)
      .collect(java.util.stream.Collectors.toMap(ModuleLocationProjection::getModuleId,
        ModuleLocationProjection::getLocation));
  }

  private static void removeUnavailableCandidates(Map<String, ScopedData> candidates,
    Map<String, String> locations) {
    candidates.entrySet().removeIf(candidate -> !locations.containsKey(candidate.getKey()));
  }

  private EgressBootstrap buildCachedResult(Map<String, ScopedData> candidates, Map<String, String> locations,
    List<String> requiredInterfaces) {
    var selected = candidates.values().stream().sorted(comparing(value -> value.data().getModuleId()))
      .collect(java.util.stream.Collectors.toMap(value -> value.data().getName(), value -> value,
        this::highestVersion, LinkedHashMap::new));
    var result = selected.values().stream().sorted(comparing(value -> value.data().getModuleId()))
      .map(value -> toDiscovery(value.data(), value.applicationId(), locations.get(value.data().getModuleId()),
        requiredInterfaces)).toList();
    return new EgressBootstrap().requiredModules(result);
  }

  private ScopedData highestVersion(ScopedData first, ScopedData second) {
    var comparison = new Semver(first.data().getVersion()).compareTo(new Semver(second.data().getVersion()));
    if (comparison != 0) {
      return comparison > 0 ? first : second;
    }
    return first.data().getModuleId().compareTo(second.data().getModuleId()) >= 0 ? first : second;
  }

  private EgressBootstrap getLegacyEgressBootstrap(String moduleId, List<String> applicationIds) {
    var views = repository.findAllRequiredByModuleIdAndApplicationIdsIn(moduleId, applicationIds);
    var moduleView = removeModuleViewById(moduleId, views);
    return new EgressBootstrap().requiredModules(resolveLegacy(moduleView, views));
  }

  private List<ModuleBootstrapDiscovery> resolveLegacy(ModuleBootstrapView moduleView,
    List<ModuleBootstrapView> views) {
    var requiredInterfaces = getRequiredOptionalInterfaces(moduleView.getDescriptor());
    if (requiredInterfaces.isEmpty()) {
      return List.of();
    }
    var discoveries = views.stream().map(view -> {
      var provides = view.getDescriptor().getProvides();
      if (provides != null) {
        provides.removeIf(provided -> !requiredInterfaces.contains(provided.getId()));
      }
      return mapper.convert(view);
    }).collect(toList());
    var unique = new LinkedHashMap<String, ModuleBootstrapDiscovery>();
    discoveries.forEach(discovery -> unique.merge(getNameAndVersion(discovery.getModuleId()).getLeft(), discovery,
      (first, second) -> compareVersions(first, second) >= 0 ? first : second));
    return unique.values().stream().toList();
  }

  private static int compareVersions(ModuleBootstrapDiscovery first, ModuleBootstrapDiscovery second) {
    var firstVersion = getNameAndVersion(first.getModuleId()).getRight();
    var secondVersion = getNameAndVersion(second.getModuleId()).getRight();
    var comparison = InterfaceComparisonUtils.compare("", firstVersion, "", secondVersion);
    return comparison == 0 ? first.getModuleId().compareTo(second.getModuleId()) : comparison;
  }

  private static Predicate<InterfaceDescriptor> distinctInterfaces() {
    var interfaceIds = new HashSet<String>();
    return descriptor -> interfaceIds.add(descriptor.getId());
  }

  private static boolean hasRequiredInterface(ModuleDescriptor descriptor, List<String> requiredInterfaces) {
    return descriptor.getProvides() != null && descriptor.getProvides().stream()
      .anyMatch(provided -> requiredInterfaces.contains(provided.getId()));
  }

  private ModuleBootstrapDiscovery toDiscovery(ModuleBootstrapData data, String applicationId, String location,
    List<String> requiredInterfaces) {
    var interfaces = data.getDescriptor().getProvides() == null ? List.<InterfaceDescriptor>of()
      : data.getDescriptor().getProvides().stream().filter(provided -> requiredInterfaces.contains(provided.getId()))
      .filter(distinctInterfaces()).toList();
    return new ModuleBootstrapDiscovery().moduleId(data.getModuleId()).applicationId(applicationId).location(location)
      .systemUserRequired(data.isSystemUserRequired()).interfaces(interfaces.stream().map(this::toInterface).toList());
  }

  private ModuleBootstrapInterface toInterface(InterfaceDescriptor descriptor) {
    var endpoints = descriptor.getHandlers() == null ? List.<RoutingEntry>of() : descriptor.getHandlers();
    return new ModuleBootstrapInterface().id(descriptor.getId()).version(descriptor.getVersion())
      .interfaceType(descriptor.getInterfaceType()).endpoints(endpoints.stream().map(mapper::convert).toList());
  }

  private static List<String> getRequiredOptionalInterfaces(ModuleDescriptor descriptor) {
    return java.util.stream.Stream.concat(toStream(descriptor.getRequires()), toStream(descriptor.getOptional()))
      .map(InterfaceReference::getId).distinct().toList();
  }

  private ModuleBootstrapView removeModuleViewById(String moduleId, List<ModuleBootstrapView> result) {
    var view = result.stream().filter(m -> moduleId.equals(m.getId())).findFirst()
      .orElseThrow(() -> new EntityNotFoundException("Module not found by id: " + moduleId));
    result.remove(view);
    return view;
  }

  private static void validateApplicationIds(List<String> applicationIds) {
    if (applicationIds == null || applicationIds.isEmpty() || applicationIds.stream().anyMatch(id -> id == null
      || id.isBlank())) {
      throw new IllegalArgumentException("applicationIds must not contain null or blank items");
    }
  }

  private record ScopedData(ModuleBootstrapData data, String applicationId) {}
}
