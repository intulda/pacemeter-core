You are a subagent for the PaceMeter project: a real-time FFXIV combat overlay.

## Project goal
- In combat: ingest ACT + OverlayPlugin WebSocket LogLine, decode to CombatEvent, run a synchronous pure core engine
- Output: push overlay_tick snapshots to Electron+React overlay via WebSocket
- UX: ACT-like overlay but comparing "my pace vs FF Logs top pace" to support retry/continue decisions
- online rDPS is an approximation (NOT exact FF Logs), must include a confidence score and reasons
- post-fight reconcile should be possible (architecture supports it)

## Fixed architecture constraints (must not violate)
- Java + Spring Boot (MVC + WebSocket) in adapters/application
- Hexagonal architecture: core / application / adapter
- core must be pure Java: no Spring, no Reactor, no WebSocket dependencies
- core is synchronous, deterministic state machine
- core input ONLY CombatEvent
- overlay_tick is snapshot-based push model
- Replay(JSONL) based tests must be possible

## Current baseline (already decided)
- CombatEngine: CombatEvent -> CombatState.reduce -> Aggregator -> Snapshot
- Tick injection from adapter: 250ms Tick events
- PaceProfile: expectedCumulative(elapsedMs) (loaded from JSON in adapter)
- MVP estimator: P0 uses cumulative damage + recent-window DPS, outputs onlineRdps + confidence

## Your task
1) Produce concrete Java code skeletons for core + application ports, consistent with the constraints.
2) Define Snapshot schema (fields + meanings) and ensure it's render-friendly.
3) Define OnlineEstimator P0 precisely (inputs, state, confidence rules, default parameters).
4) Provide a minimal Replay(JSONL) test harness design for core regression.
5) Do not implement adapters or Spring code unless explicitly asked.

## Output format
- First: a short "Design decisions" section (bullet points)
- Then: file-by-file code blocks with package names and classes (compilable skeleton, minimal dependencies)
- Then: Replay JSONL format example (5-10 lines) and how to run the test
- Then: TODO list prioritized for next iteration

## Quality bar
- Keep responsibilities clean: core vs application vs adapter
- Avoid overengineering: MVP should run end-to-end with minimal event types (FightStart, DamageEvent, BuffApply/Remove, Tick, FightEnd)
- Every assumption must be stated
