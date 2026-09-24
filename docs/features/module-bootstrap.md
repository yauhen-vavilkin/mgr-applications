---
feature_id: module-bootstrap
title: Module Bootstrap (Ingress and Scoped Egress)
updated: 2026-06-17
---

# Module Bootstrap (Ingress and Scoped Egress)

## What it does

Adds two endpoints used by the FOLIO module sidecar to build its routing tables:

| Method | Path | Description |
|--------|------|-------------|
| GET | /modules/{id}/bootstrap | Ingress: the module's own discovery and provided routes only (empty `requiredModules`). |
| POST | /modules/{id}/bootstrap | Egress: discovery for the module's required/optional providers, restricted to the application scope in the request body. |

The pre-existing `GET /modules/{id}` (full, globally-resolved bootstrap) is unchanged.

## Request / response

- **Egress request** (`egressBootstrapRequest`): `{ "applicationIds": ["app-a-1.0.0", ...] }` (required, at least one id).
- **Ingress response**: `moduleBootstrap` with `module` populated and `requiredModules: []`.
- **Egress response** (`egressBootstrap`): `{ "requiredModules": [...] }` — providers resolved within the supplied applications only; the self module is not returned.

## Business rules

- Egress required-module resolution only considers providers whose owning application is in `applicationIds`.
- If the requested module is not present in the supplied scope, egress returns `404`.
- Empty/missing `applicationIds` returns `400`.
- Required modules are filtered to the interfaces the module actually requires/optional, and deduplicated by name keeping the highest version (same as `GET /modules/{id}`).
- With `MODULE_BOOTSTRAP_CACHE_ENABLED=true` (the default), backend module descriptors are cached one entry per application. Each entry includes an interface-provider index; missing applications and applications without backend modules are cached as empty.
- Discovery locations are never cached: candidate locations are read from the database for every request, so discovery changes are visible immediately on every instance.
- Application and discovery writes advance a shared database cache version. `MODULE_BOOTSTRAP_CACHE_TTL` (default `1h`) is measured after last access, and `MODULE_BOOTSTRAP_CACHE_MAX_SIZE` (default `1000`) bounds entries. Setting the enabled variable to `false` uses the uncached query path.

