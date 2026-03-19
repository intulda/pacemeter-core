# Parity Continuation Handoff (2026-03-19)

## 1) 재시작 기준 원칙 (작업 방식 고정)
- **측정 우선**: 코드 변경 전/후로 반드시 same command로 heavy4 + heavy2 + rollup 재측정.
- **단일 가설 변경**: 한 번에 하나만 바꾸고, 수치 악화 시 즉시 롤백.
- **중복 시도 금지**: 이미 실패한 패턴은 재시도하지 않음.
- **증거 기반**: line-type/GUID/event 증거가 없는 보정 금지.

## 2) 현재 기준선 (이 상태에서 시작)
- heavy4 (`2026-03-15-heavy4-vafpbaqjnhbk1mtw`, fight=2):
  - `MAPE ~= 0.01196`, `p95 ~= 0.02282`, `max ~= 0.02505`
- heavy2 (`2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`, fight=6):
  - `MAPE ~= 0.01581`, `p95 ~= 0.03460`, `max ~= 0.03696`
- rollup (3 submissions / 24 actors):
  - `mape ~= 0.01264`, `p95 ~= 0.03595`, `max ~= 0.03747`
  - gate: `p95<=0.03`만 미충족

## 3) 확정된 원인/결론
- 큰 구조 원인 1개 해결됨:
  - `fflogsFightId` 명시값이 있는데 내부 휴리스틱으로 다른 fight로 덮어쓰던 문제 수정 완료.
  - heavy2 selected fight가 `2 -> 6` 정상화.
- 남은 문제는 fight 선택이 아니라 attribution/coverage 축.

## 4) 실패해서 금지된 시도 (재시도 금지)
- `status=0` DoT에서 source 귀속을 snapshot redistribution보다 전면 우선.
- unknown-source attribution이 있으면 snapshot redistribution 스킵.
- `UNKNOWN_STATUS_DOT_WINDOW_MS`를 90s→35s로 축소.
- 위 3개는 heavy2/heavy4/rollup 중 최소 1개 이상 악화 확인됨.

## 5) 유지된 변경
- `known-status + unknown-source` DoT에 대해 recent status/application evidence로 source/action 복원 경로 추가.
- 회귀 테스트:
  - `dotTick_withKnownStatusAndUnknownSource_usesRecentStatusEvidence`

## 6) 다음에 할 일 (다른 컴퓨터에서 그대로 시작)
1. heavy2 잔차 상위 actor(`SAM/SCH/WHM`)를 **target 단위**로 분해.
2. `local emitted DamageEvent`와 `FFLogs events(ability)`를 target/source/action별로 1:1 집계 비교.
3. 차이가 나는 축이
   - `누락`인지
   - `중복`인지
   - `잘못된 source 귀속`인지
   먼저 분리하고, 그 축만 수정.
4. 수정 후 반드시 baseline command로 재측정.

## 7) 실행 커맨드 (재현용)
기본 회귀:
```bash
./gradlew test --tests com.bohouse.pacemeter.application.ActIngestionServiceTest --tests com.bohouse.pacemeter.application.SubmissionParityReportServiceTest --tests com.bohouse.pacemeter.application.SubmissionParityRegressionGateTest
```

parity 측정:
```bash
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6Parity_withConfiguredFflogsCredentials_printsActorDelta \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors
```

잔차 분해 진단:
```bash
PACE_FFLOGS_CLIENT_ID=... PACE_FFLOGS_CLIENT_SECRET=... ./gradlew test \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6WorstActorLineTypeEvidence_printsUnknownSkillCorrelations \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6WorstActorGuidSkillDelta_printsActionLevelMismatch \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6GuidParityFromIngestion_printsEmitVsFflogsTotals
```

## 8) 참고 파일
- 진행 로그: `docs/parity-patch-notes.md`
- 이전 핸드오프: `docs/home-test-handoff-2026-03-19.md`
- 이번 핸드오프: `docs/parity-continuation-handoff-2026-03-19.md`
