# Home Test Handoff (2026-03-19)

## 1) 지금까지 진행한 핵심
- DoT unknown-status 귀속 로직을 `ActIngestionService` 밖으로 분리:
  - `UnknownStatusDotAttributionResolver` 신설
  - service 내부 중복 후보 수집/결정 로직 정리
- snapshot 전송 데이터 확장:
  - `deathCount`, `maxHitDamage`, `maxHitSkillName` 추가 경로 연결
  - `ActorStats` / `CombatState` / `SnapshotAggregator` / `ActorSnapshot` 반영
- parity 진단 테스트 강화:
  - heavy2 fight6 기준 unknown-skill/line-type/GUID mismatch 자동 출력 진단 추가
  - FFLogs `abilities table` 기준 비교 경로 확인

## 2) 오늘 기준 확인된 포인트
- heavy2 fight6에서 unknown-skill이 전체 직업 공통 폭증은 아님.
  - PCT(바나바나) 중심으로 `DoT#0` 2건 확인
- 큰 오차 축은 GUID 단위 불일치가 더 큼.
  - 예: `4094`, `409C`, `1D41`은 local 부족, `8780`은 local 과다
- `eventsByAbility`와 `abilities table`은 일부 GUID에서 값이 달라서,
  - 원인분석 기준은 `abilities table` 우선이 맞음

## 3) 집에서 실행할 테스트 (우선순위 순)
환경변수:
- `PACE_FFLOGS_CLIENT_ID`
- `PACE_FFLOGS_CLIENT_SECRET`

실행 명령:
```bash
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test --tests com.bohouse.pacemeter.application.SubmissionParityRegressionGateTest
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6WorstActorLineTypeEvidence_printsUnknownSkillCorrelations
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6PctUnknownEvents_printsRawToFflogsCandidates
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6WorstActorGuidSkillDelta_printsActionLevelMismatch
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6GuidParityFromIngestion_printsEmitVsFflogsTotals
```

결과 확인:
- XML: `build/test-results/test/TEST-*.xml`
- HTML: `build/reports/tests/test/index.html`

## 4) 현재 WIP (내일 이어서 할 지점)
- `SubmissionParityReportService.runReplay`에서
  - skill breakdown 집계를 raw 재해석 기반이 아니라
  - 실제 emitted `DamageEvent` 기반으로 전환 중
- 목적:
  - 진단값(local skill sum)과 엔진 최종 산출 경로를 일치시켜
  - 분석 착시를 줄이고 원인 추적 정확도 확보

## 5) 주의사항
- `SubmissionParityReportServiceTest` 실행 시 아래 fixture가 없으면 실패할 수 있음:
  - `data/submissions/2026-02-11-heavy3-pull1-full/metadata.json`
- 이 실패는 현재 리팩토링 로직 자체 이슈와 분리해서 봐야 함.

## 6) 변경 파일(요약)
- `src/main/java/com/bohouse/pacemeter/application/UnknownStatusDotAttributionResolver.java`
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- `src/main/java/com/bohouse/pacemeter/application/SubmissionParityReportService.java` (WIP)
- `src/main/java/com/bohouse/pacemeter/core/model/ActorStats.java`
- `src/main/java/com/bohouse/pacemeter/core/model/CombatState.java`
- `src/main/java/com/bohouse/pacemeter/core/snapshot/ActorSnapshot.java`
- `src/main/java/com/bohouse/pacemeter/core/snapshot/SnapshotAggregator.java`
- `src/test/java/com/bohouse/pacemeter/application/SubmissionParityReportDiagnostics.java`
- `src/test/java/com/bohouse/pacemeter/application/SubmissionParityRegressionGateTest.java`
- `src/test/java/com/bohouse/pacemeter/application/UnknownStatusDotAttributionResolverTest.java`
- 진행 로그: `docs/parity-patch-notes.md`
