package org.folio.am.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "module_bootstrap_cache_version")
public class ModuleBootstrapCacheVersionEntity {
  @Id
  private Long id;
  private long version;
}
