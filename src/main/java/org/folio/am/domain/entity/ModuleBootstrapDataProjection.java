package org.folio.am.domain.entity;

/** Projection for application-scoped bootstrap data without discovery information. */
public interface ModuleBootstrapDataProjection {
  String getModuleId();

  String getApplicationId();

  String getName();

  String getVersion();

  boolean getSystemUserRequired();

  String getDescriptor();
}
