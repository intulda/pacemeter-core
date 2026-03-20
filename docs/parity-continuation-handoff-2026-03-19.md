# Parity Continuation Handoff (2026-03-20, Updated)

## 1) 작업 방식 고정
- same command로 heavy4/heavy2/rollup을 항상 전후 비교한다.
- 한 번에 한 가설만 바꾸고, 악화되면 즉시 롤백한다.
- 실패한 시도를 반복하지 않는다.
- 증거(line type/GUID/event)가 없는 보정은 금지한다.

## 2) 현재 기준선 (이 상태에서 시작)
- heavy4 (`2026-03-15-heavy4-vafpbaqjnhbk1mtw`, fight=2):
  - `MAPE≈0.01130`, `p95≈0.02338`, `max≈0.02826`
- heavy2 (`2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`, fight=6):
  - `MAPE≈0.01606`, `p95≈0.03166`, `max≈0.03426`
- rollup (3 submissions / 24 actors):
  - `mape≈0.01184`, `p95≈0.02804`, `max≈0.03426`
  - gate `pass=true`

## 3) 확정된 변경
- `SubmissionParityReportService`:
  - metadata에 명시된 `fflogsFightId`가 유효할 때 휴리스틱으로 덮어쓰지 않게 수정
  - heavy2 selected fight가 `2 -> 6` 정상화
- `ActIngestionService`:
  - `known-status + unknown-source` DoT를 recent status/application evidence로 source/action 복원
- `ActIngestionService` (2026-03-20 복구):
  - 실험 경로(TrackedSource 우선 분기) 제거
  - unknown-status DoT 처리 순서를 안정 경로로 복원
- 테스트 보강:
  - `SubmissionParityReportServiceTest`에 explicit fight-id 유지 회귀
  - `ActIngestionServiceTest.dotTick_withKnownStatusAndUnknownSource_usesRecentStatusEvidence`

## 4) 실패 후 롤백된 시도 (재시도 금지)
- `status=0`에서 source 귀속을 snapshot redistribution보다 전면 우선
- unknown-source attribution 존재 시 snapshot redistribution 스킵
- `UNKNOWN_STATUS_DOT_WINDOW_MS 90s -> 35s`
- 위 3개는 heavy2/heavy4/rollup 중 1개 이상 확실히 악화
- `resolveTrackedSourceDots` 기반 분기 실험 (공통 급락 원인) 재도입 금지

## 5) 이번 턴 신규 진단과 결론
- 신규 진단:
  - `debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas`
- 결론:
  - `eventsByAbility`는 heavy2의 `SAM/SCH/WHM` DoT 축에서 0 또는 과소가 자주 발생
  - 즉 원인 판단 기준은 계속 `abilities table` 우선
  - `eventsByAbility`는 보조 증거로만 사용

## 6) 다음 작업 (다른 컴퓨터에서 바로 시작)
1. heavy2 상위 잔차(`SAM/SCH/WHM`)의 local `totalDamage` 차이를 ability table 기준으로 다시 분해
2. `status=0` tick의 actor 귀속이 과대/과소인 축을 line-type 근거로 좁힌다
3. 수정은 `one-shot`으로 하지 말고 한 가설씩 측정/기록
4. heavy4/rollup 동시 악화 시 즉시 롤백

## 7) 실행 커맨드
회귀:
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
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6GuidParityFromIngestion_printsEmitVsFflogsTotals \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas
```

## 8) 참고 문서
- `docs/parity-patch-notes.md`
- `docs/home-test-handoff-2026-03-19.md`
- `docs/dot-attribution-catalog-maintenance.md`

## 9) 2026-03-20 추가 리팩토링(동작 보존)
- `UnknownStatusDotAttributionResolver`에 `resolveKnownStatusUnknownSourceAttribution()` 추가.
- `ActIngestionService.resolveKnownStatusUnknownSourceAttribution()`의 중복 루프 제거 후 resolver 호출로 단일화.
- 신규 테스트:
  - `UnknownStatusDotAttributionResolverTest.resolveKnownStatusUnknownSourceAttribution_prefersCorroboratedSource`
  - `DotAttributionCatalogTest` (카탈로그 무결성/안정 job set 검증)
- 검증:
  - `ActIngestionServiceTest`, `UnknownStatusDotAttributionResolverTest`, `DotAttributionCatalogTest` 통과
  - `./scripts/parity_repro_check.sh` 재측정 후 gate `pass=true` 유지

## 10) 2026-03-20 추가 진단/실패 실험 기록
- 신규 진단:
  - `SubmissionParityReportDiagnostics.debugHeavy2Fight6SnapshotWeightVsAbilityTotals_printsSamSchWhmShares`
- 핵심 관측:
  - heavy2 fight6에서 snapshot share(SAM/SCH/WHM)가 FFLogs ability share와 반대 방향.
- 실패 실험(재시도 금지):
  - snapshot redistribution을 actor 균등 분배로 변경
  - heavy4/heavy2/rollup 동시 악화, gate `pass=false`
  - 즉시 롤백 완료

## 11) 2026-03-20 추가 성공 패치 (현재 기준선)
- 적용:
  - `type 37` 파서 정렬 교정(4-slot 단위) + 다중 status/source 신호 파싱.
  - `ActIngestionService` snapshot 분배에 `최근 3.5초 type37 signal 빈도` 블렌드 가중치 추가.
    - `STATUS_SIGNAL_WEIGHT_BLEND_ALPHA=0.80`
    - signal key 수 기반 confidence 스케일 적용:
      - 1개 key: 절반 알파
      - 2개 이상 key: 전체 알파
  - 기존 active tracked dot 교집합 우선 로직 유지.
- 회귀:
  - 관련 parser/ingestion 테스트 통과.
  - `./scripts/parity_repro_check.sh` gate `pass=true` 유지.
- 수치(3 submissions rollup):
  - 이전: `mape=0.01184`, `p95=0.02804`, `max=0.03426`
  - 현재: `mape=0.01128`, `p95=0.02529`, `max=0.03080`
- heavy2 개선:
  - `MAPE 0.01606 -> 0.01379`
  - `p95 0.03166 -> 0.02464`
  - `max 0.03426 -> 0.02578`

## 12) 2026-03-20 추가 성공 패치 (auto-attack DoT 오귀속 제거)
- 진단 확정:
  - heavy4 PLD에서 `DoT#17`이 대량 집계되며 `0x17(Attack)` 과집계 유발.
  - 원인은 카탈로그의 `status 248 -> action 23(0x17)` 오매핑.
- 적용:
  - `DotAttributionCatalog`에서 auto-attack action id(`0x7`, `0x17`)를 DoT 매핑에서 전역 제외.
  - 동일 규칙을 `build_dot_attribution_catalog.py`에 반영.
  - `dot-attribution-catalog.json`의 PLD(19) mapping 제거.
  - `SubmissionParityRegressionGateTest`에 FFLogs credential reflection 주입 추가(게이트 불안정 해소).
- 현재 최신 기준선 (`./scripts/parity_repro_check.sh`):
  - rollup:
    - `mape=0.01026`
    - `p95=0.01649`
    - `max=0.02225`
  - heavy4:
    - `MAPE=0.01101`, `p95=0.02025`, `max=0.02225`
  - lindwurm:
    - `MAPE=0.00818`, `p95=0.01530`, `max=0.01604`
  - heavy2:
    - `MAPE=0.01160`, `p95=0.01606`, `max=0.01616`

## 13) 2026-03-20 진행 추가 (신뢰성 강화 1차)
- 적용:
  - `ActIngestionService`에 DoT invalid action id 드롭 가드 추가:
    - `0x07`, `0x17`은 DoT DamageEvent 미발행.
  - `SubmissionParityQualityService`의 `worstActors` 항목에
    `topSkillDeltas`(상위 5개 skill delta, local guid hex 포함)를 추가.
- 테스트:
  - `ActIngestionServiceTest.dotTick_withAutoAttackLikeStatusId_dropsInvalidDotAction`
  - `SubmissionParityQualityServiceTest`
  - `SubmissionParityRegressionGateTest`
  - `DotAttributionCatalogTest`
  - 모두 통과.
- 재측정:
  - `./scripts/parity_repro_check.sh` 결과 유지:
    - rollup `mape=0.01026`, `p95=0.01649`, `max=0.02225`, gate `pass=true`.
- 다음 바로 할 일:
  - `topSkillDeltas` 기준으로 heavy4 SCH/DRG와 heavy2 SAM/WHM의 공통 편향(특히 DoT/auto-attack 경계)을
    한 번 더 규칙화해 p95를 `1.4%`대로 낮추는 작업.

## 14) 2026-03-20 진행 추가 (진단 정밀화 + known-status unknown-source fallback)
- 적용:
  - `SubmissionParityReport.SkillBreakdownEntry`에 `skillGuid` 필드 추가.
  - `SubmissionParityReportService`에서
    - local skill name에서 guid 추출해 `skillGuid` 채움
    - FFLogs ability의 `guid`를 그대로 `skillGuid`로 채움
  - `SubmissionParityQualityService.buildTopSkillDeltas()`를 GUID 우선 매칭으로 변경.
  - 동일 GUID가 local에 여러 엔트리(`DoT#409C`, `Skill#409C`)로 존재할 때
    덮어쓰기하지 않고 합산하도록 수정(진단 왜곡 제거).
  - `ActIngestionService.resolveKnownStatusUnknownSourceAttribution()`에 안전 fallback 추가:
    - evidence 기반 복원 실패 시
    - known status를 가진 unknown-source DoT에 대해
    - 파티 내 해당 status를 가진 job 후보가 정확히 1명일 때만 source/action 귀속.
    - job 후보가 2명 이상이면 귀속하지 않음(과보정 방지).
- 테스트:
  - `ActIngestionServiceTest` 신규 2건:
    - `dotTick_withKnownStatusAndUnknownSource_usesUniqueJobFallbackWithoutRecentEvidence`
    - `dotTick_withKnownStatusAndUnknownSource_doesNotFallbackWhenMatchingJobIsAmbiguous`
  - 기존 `ActIngestionServiceTest`, `SubmissionParityQualityServiceTest` 통과.
- 측정:
  - `./scripts/parity_repro_check.sh` 재측정 결과는 baseline과 동일:
    - rollup `mape=0.0102602959`, `p95=0.0164872692`, `max=0.0222490071`, gate `pass=true`.
- 해석:
  - 이번 사이클은 rDPS 수치 개선이 아니라 원인 진단 정확도 개선 단계.
  - `topSkillDeltas`가 실제 결손 축을 정확히 보여주기 시작했고,
    다음 사이클에서 DoT 결손(예: DRG 64AC, SCH 9094, WHM 4094)을
    이벤트 선택/귀속 규칙으로 직접 줄일 수 있는 기반을 확보.

## 15) 2026-03-20 실패 시도 기록 (재시도 금지)
- 시도:
  - `applyStatusSignalWeighting()`에서
    snapshot에 없는 key라도 `active dot + recent type37 signal`이면
    신규 분배 후보에 넣는 확장 로직 적용.
- 결과:
  - rollup 악화:
    - `mape=0.0102603 -> 0.0105053`
    - `p95=0.0164873 -> 0.0218130`
    - `max=0.0222490 -> 0.0237461`
  - heavy2 악화:
    - `MAPE=0.0115958 -> 0.0126042`
    - `p95=0.0160617 -> 0.0209838`
- 조치:
  - 해당 확장 로직 즉시 롤백.
  - `ActIngestionServiceTest`에 추가했던 관련 신규 테스트도 제거.
  - baseline 재복구 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.

## 16) 2026-03-20 실패 시도 기록 (재시도 금지)
- 시도:
  - `selectSnapshotRedistributionWeights()`에서
    active DoT는 있는데 snapshot key 교집합이 없으면 snapshot fallback을 차단.
  - 목적: `status=0` 과귀속(특히 heavy2 SAM `1D41`) 축소.
- 결과:
  - `SubmissionParityRegressionGateTest`가 즉시 실패.
  - 관측치:
    - `p95 APE = 0.4471232655894308` (gate 목표 `<= 0.03` 대비 대폭 악화)
- 원인:
  - 현재 `wouldEmitDotDamage()`는 snapshot redistribution 비어 있으면 false를 반환.
  - 위 변경이 해당 분기와 결합되어 DoT 이벤트 대량 누락으로 이어짐.
- 조치:
  - 변경 내용 즉시 롤백(코드 + 테스트).
  - `./scripts/parity_repro_check.sh`로 baseline 재복구 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.

## 17) 2026-03-20 적용 변경 (snapshot weight smoothing)
- 적용:
  - `ActIngestionService.noteStatusSnapshot()`에서 snapshot weight를
    `pow(value, gamma)`로 완만화한 뒤 분배 가중치로 사용.
  - 채택값: `STATUS_SNAPSHOT_WEIGHT_GAMMA = 0.85`.
- 의도:
  - `status=0` 분배 시 특정 actor로 쏠리는 극단 가중치 완화.
  - 로그/직업 하드코딩 없이 일반화된 분배 안정화.
- 검증:
  - `ActIngestionServiceTest` 통과
  - `SubmissionParityRegressionGateTest` 통과
  - `./scripts/parity_repro_check.sh` gate `pass=true`
- 최신 수치:
  - rollup: `mape=0.0099661`, `p95=0.0169417`, `max=0.0220786`
  - heavy4: `MAPE=0.0109641`, `p95=0.0202885`, `max=0.0220786`
  - lindwurm: `MAPE=0.0081352`, `p95=0.0151614`, `max=0.0160286`
  - heavy2: `MAPE=0.0107990`, `p95=0.0159162`, `max=0.0168157`
