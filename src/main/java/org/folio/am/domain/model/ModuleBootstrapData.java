package org.folio.am.domain.model;

import lombok.Builder;
import lombok.Value;
import org.folio.common.domain.model.ModuleDescriptor;

/** Module data used to build bootstrap responses without retaining discovery locations. */
@Value
@Builder
public class ModuleBootstrapData {
  String moduleId;
  String applicationId;
  String name;
  String version;
  boolean systemUserRequired;
  ModuleDescriptor descriptor;
}
