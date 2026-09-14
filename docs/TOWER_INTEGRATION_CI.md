# Tower integration CI contract

## Notes

### Exact functionality

The CobbleRaids CI workflow now runs on `feature/tower-integration-api` in addition to `main` and pull requests. It executes the existing validation suite, the normal Loom build, runtime-JAR validation, and `validation/validate_tower_integration_api.py`.

### Architectural role

The new validator ensures the addon-facing `com.cobbleraids.api` package does not expose CobbleRaids internal lifecycle, raid, spawn, reward, or mixin types. This keeps CobbleTowers insulated from implementation churn.

### Performance impact

These checks run only in CI and have no server runtime cost.

### Validation boundary

The static API-boundary validator is not a replacement for lifecycle behavior tests. External-encounter reward suppression, exactly-once completion notification, failure cleanup, and ordinary raid regression behavior must be covered before the callable Tower encounter API is considered complete.
