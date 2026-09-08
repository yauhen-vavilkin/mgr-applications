package org.folio.am.service;

import lombok.RequiredArgsConstructor;
import org.folio.am.repository.ModuleBootstrapCacheVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModuleBootstrapCacheVersionService {
  private final ModuleBootstrapCacheVersionRepository repository;

  @Transactional
  public void increment() {
    repository.incrementVersion();
  }
}
