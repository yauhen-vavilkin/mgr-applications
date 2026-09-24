package org.folio.am.repository;

import org.folio.am.domain.entity.ModuleBootstrapCacheVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ModuleBootstrapCacheVersionRepository extends JpaRepository<ModuleBootstrapCacheVersion, Long> {

  @Query("select version from ModuleBootstrapCacheVersion where id = 1")
  long getVersion();

  @Modifying
  @Query("update ModuleBootstrapCacheVersion set version = version + 1 where id = 1")
  int incrementVersion();
}
