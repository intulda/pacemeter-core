# FFLogs Auto-Crit / Auto-DHit Design

## Date
- 2026-03-30

## Background
- `docs/FFLogs Buff Allocation Math.docx` documents the Endwalker-and-beyond handling for guaranteed crit/direct hit actions.
- Current `DamageEvent` only carries final outcome booleans (`criticalHit`, `directHit`).
- That is enough for ordinary probability splitting, but not enough to distinguish:
  - natural crit/dhit
  - buff-caused crit/dhit
  - job-mechanic guaranteed crit/dhit
  - Patch 6.2 external crit/dhit rate buffs that convert into damage multipliers on guaranteed hits

## Constraint
- ACT input does not always let us prove whether a hit was guaranteed crit/dhit.
- A boolean field would force a wrong certainty in ambiguous cases.

## Decision
- Extend `CombatEvent.DamageEvent` with `HitOutcomeContext`.
- `HitOutcomeContext` carries:
  - `autoCrit: AutoHitFlag`
  - `autoDirectHit: AutoHitFlag`
- `AutoHitFlag` values:
  - `YES`
  - `NO`
  - `UNKNOWN`

## Why `UNKNOWN`
- We need to preserve current behavior when the ingestion layer cannot prove auto-hit semantics.
- `UNKNOWN` should behave like today's model for now.
- Later, ingestion or a job/action rule catalog can upgrade `UNKNOWN` to `YES` or `NO` when evidence is strong enough.

## Intended math changes
- `autoCrit=YES`
  - external crit-rate buffs do not receive ordinary crit-proc attribution
  - external crit-rate buffs may still receive Patch 6.2 auto-crit damage-multiplier attribution
- `autoDirectHit=YES`
  - same rule for direct-hit rate buffs
- `autoCrit=YES && autoDirectHit=YES`
  - no ordinary crit/dhit proc attribution
  - only external rate-buff multiplier attribution remains
- `UNKNOWN`
  - keep current probabilistic splitting until we can classify better

## Rollout plan
1. Add `HitOutcomeContext` to `DamageEvent` with default `UNKNOWN`.
2. Keep ingestion emitting `UNKNOWN` everywhere first.
3. Update `CombatState` attribution math to branch on `autoCrit/autoDirectHit`.
4. Add focused tests for:
   - auto crit
   - auto dhit
   - auto crit+dHit
   - unknown fallback parity
5. Add optional action/job rule provider to classify guaranteed-hit actions.

## Scope note
- This document only covers event-model and attribution design.
- It does not yet decide how to infer guaranteed-hit actions from ACT logs; that should be isolated behind a catalog/provider instead of being hardcoded into ingestion flow.
