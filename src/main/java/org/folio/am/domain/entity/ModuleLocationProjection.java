package org.folio.am.domain.entity;

/** Projection for the uncached discovery location of a module. */
public interface ModuleLocationProjection {

  String getModuleId();

  String getLocation();
}
