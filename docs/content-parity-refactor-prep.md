# Content Parity Refactor Prep (2026-04-23)

## Scope
- Goal: `all contents parity` (heavy/savage/ultimate included) for FFLogs live companion.
- This document inventories current hard-coupled branches and prepares phased refactoring.

## Findings

### 1) Production: Heavy-name/fight-id hardcoding
- No direct hardcoding found in production logic for:
  - `"heavy*"` / `"lindwurm"` names
  - specific `fightId` values
  - specific submission IDs

### 2) Production: Action-specific hardcoding that can bias generalization
- File: `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- Current hardcoded specialization:
  - `CHAOTIC_SPRING_ACTION_ID = 0x64AC`
  - `HIGANBANA_ACTION_ID = 0x1D41`
  - Method `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
    - requires `recentExactActionId == CHAOTIC_SPRING_ACTION_ID`
    - requires foreign tracked dot with `HIGANBANA_ACTION_ID`
    - applies `KNOWN_SOURCE_FOREIGN_HIGANBANA_WEIGHT_FACTOR`
- Impact:
  - Not heavy-name specific, but SAM/DRG action-pair specific.
  - This is a parity heuristic likely tuned from heavy residuals and may not generalize.

### 3) Production: Content coverage limitation in zone mapping
- File: `src/main/java/com/bohouse/pacemeter/adapter/outbound/fflogsapi/FflogsZoneLookup.java`
- File: `src/main/resources/fflogs-zones.json`
- Current behavior/documentation indicates savage-only territory mapping.
- Impact:
  - For non-mapped contents (for example ultimates), zone-to-encounter resolution can fail or degrade.
  - This is a direct blocker for all-content parity guarantees.

### 4) Test/diagnostics: heavy/lindwurm hardcoded harness
- File: `src/test/java/com/bohouse/pacemeter/application/SubmissionParityRegressionGateTest.java`
  - fixed submissions: heavy4/lindwurm/heavy2
  - additional gate focused on heavy2 all-fights
- File: `src/test/java/com/bohouse/pacemeter/application/SubmissionParityReportDiagnostics.java`
  - extensive hardcoded submission IDs and fight IDs for heavy/lindwurm scenarios
- Impact:
  - Validation strategy is currently heavy-centric; cannot prove all-content parity.

## Refactor Preparation Plan

### Phase 1: Decouple content set from test code (no behavior change)
1. Introduce a test fixture catalog file (for example `src/test/resources/parity-fixtures.json`) with:
   - submissionId
   - expected content group (`heavy`, `savage`, `ultimate`, ...)
   - required minimum matched actor count
   - per-group gate thresholds
2. Change regression gate test to iterate the fixture catalog instead of hardcoded constants.
3. Keep current heavy fixtures as initial entries to preserve baseline continuity.

### Phase 2: Expand production zone coverage
1. Extend `fflogs-zones.json` from savage-only to all targeted content tiers.
2. Update `FflogsZoneLookup` comments/contract to remove savage-only assumption.
3. Add tests for representative non-savage territories (especially ultimate).

### Phase 3: Isolate action-pair heuristic behind strategy boundary
1. Extract `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit` gating into a strategy interface.
2. Move action IDs and weight into data-driven rule config (catalog), not hardcoded constants.
3. Add generic fallback strategy and make action-pair strategy opt-in via explicit rule match.

### Phase 4: Gate redesign for all-content parity
1. Add rollup slices by content group (`heavy`, `savage`, `ultimate`).
2. Enforce pass conditions:
   - global rollup gate
   - per-group gate (all must pass)
3. Keep selected-fight diagnostics as debug-only signals, not acceptance criteria.

## Immediate Checklist (next implementation turn)
1. Add `parity-fixtures.json` and migrate `SubmissionParityRegressionGateTest` constants.
2. Add fixture loader utility in test scope.
3. Add TODO markers in `FflogsZoneLookup` and `fflogs-zones.json` for all-content expansion.
4. Create a minimal strategy boundary skeleton for status=0 action-pair dampening (no behavior change yet).

## Progress Update (2026-04-23)
- Completed:
  - Fixture-driven regression gate migration (`SubmissionParityRegressionGateTest`).
  - Content-group gate support (`contentGroupGates`) with optional groups.
  - Metadata consistency check: fixture `contentGroup` must match submission `metadata.json` difficulty.
  - Fixture coverage check: all local FFLogs submissions in tracked groups must be listed in fixture gates.
  - Optional-group guard: if local FFLogs data exists for an optional group, test prompts switching to `optional=false`.
- Current limitation:
  - Local dataset currently contains only savage submissions.
  - `ultimate` group gate is configured as `optional=true` until at least one ultimate submission is added.
