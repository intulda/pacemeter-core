# Parity Patch Notes

## 2026-04-01

### Priority reminder
- 현재 메인 스트림은 `clearability`가 아니라 `live rDPS parity`다.
- 목표는 `pacemeter live rDPS ~= FFLogs companion live rDPS`.
- replay / parity report / diagnostics는 production tuning 전에 원인을 분리하는 검증 도구로 사용한다.

### DRG `64AC / Chaotic Spring` investigation
- heavy2 selected fight(`fight=6`) 기준으로 DRG actor total은 여전히 FFLogs보다 높다.
  - local `41284.5`
  - fflogs `40149.7`
  - delta `+1134.8`
- 그런데 `64AC` 자체는 "못 잡는" 문제가 아니었다.
  - local full total `1788931`
  - fflogs total `1330078`
  - delta `+458853`
- local skill breakdown에서 같은 GUID `64AC`가 두 엔트리로 분리되어 있었다.
  - `DoT#64AC = 1086661`
  - `chaotic spring (64AC) = 702270`
- 기존 parity report는 local top skills를 `merge 전에 limit`하고 있어서 shared GUID 스킬 해석이 왜곡될 수 있었다.

### This patch
- `SubmissionParityReportService`에서 local top skill 집계를 `GUID/name key로 먼저 merge`한 뒤 `damage 순 정렬 + top N limit` 하도록 수정.
- 목적은 shared GUID 스킬(`64AC` 같은 direct+DoT/alias split)의 진단 왜곡을 제거하는 것.
- 이 수정은 우선 parity diagnostics/report 해석 안정화를 위한 것이고, live attribution production math를 바꾼 것은 아니다.

### Current interpretation
- DRG `64AC` 잔여 이슈는 `status=0 DoT를 못 잡는가`보다 `shared GUID를 어떤 의미로 합산/비교해야 하는가`에 가깝다.
- selected fight 기준 DRG는 `64AC`가 부족한 것이 아니라 오히려 과집계 쪽으로 보인다.
- 따라서 다음 production 변경은 `64AC를 더 잡는 방향`이 아니라 `64AC direct/DoT/shared GUID semantics`를 먼저 분리한 뒤 결정해야 한다.

### Next step
1. `64AC`의 local split이 engine/ingestion/event emit 어디서 생기는지 분리한다.
2. FFLogs `damageDoneAbilities`가 shared GUID를 어떤 단위로 합산하는지 parity report 기준으로 맞춘다.
3. DRG 이후에는 같은 shared GUID 패턴이 있는 job/action이 더 있는지 전수 확인한다.
4. production attribution 수정은 diagnostics가 정리된 뒤에만 한다.

## 2026-03-30

### Auto-hit attribution guardrail
- `CombatState` auto-hit attribution path is now behind a feature flag.
- default: `off`
- property: `pacemeter.experimental.auto-hit-attribution.enabled=true`
- env: `PACE_EXPERIMENTAL_AUTO_HIT_ATTRIBUTION=true`
- keep the modeling scaffolding, but only enable the math explicitly for replay/parity comparison runs.

### FFLogs auto-hit design record
- `docs/FFLogs Buff Allocation Math.docx` 기준으로 guaranteed crit/direct hit 처리 설계를 분리했다.
- 핵심 문제: 현재 `DamageEvent`는 `criticalHit/directHit` 결과만 갖고 있어서, 자연 발생 / 외부 버프 유발 / job mechanic 보장타를 구분할 수 없다.
- 결정:
  - `CombatEvent.DamageEvent`에 `HitOutcomeContext(autoCrit, autoDirectHit)` 추가
  - 각 필드는 `YES/NO/UNKNOWN`
  - ingestion 기본값은 우선 `UNKNOWN`
- 이유:
  - ACT 입력만으로 auto-hit 여부를 항상 증명할 수 없으므로, 섣불리 boolean으로 확정하지 않는다.
  - `UNKNOWN`을 유지하면 현재 모델과 호환되는 상태로 점진 이행할 수 있다.
- 설계 문서: `docs/fflogs-auto-hit-design-2026-03-30.md`
- 이번 단계 범위:
  - 이벤트 모델 스캐폴딩 추가
  - replay parser가 optional `autoCrit`, `autoDirectHit`를 읽도록 확장
  - 실제 6.2 auto-hit multiplier attribution 계산은 다음 단계

### FFLogs auto-hit ingestion wiring
- `CombatState`에 auto-crit / auto-dhit 6.2 multiplier attribution 계산을 반영했다.
- `ActIngestionService`는 이제 `AutoHitCatalog`를 통해 `job + action + active self-buff` 기준으로 `HitOutcomeContext`를 채운다.
- 초기 catalog는 WAR `Inner Release + Fell Cleave / Inner Chaos / Primal Rend` 경로를 포함한다.
- self-buff active state는 ingestion 내부에서 `BuffApplyRaw` / `BuffRemoveRaw`를 통해 추적한다.
- 미분류 스킬은 계속 `UNKNOWN`을 유지한다.
- 다음 확장 포인트:
  - 직업별 guaranteed-hit rules 추가
  - action-only rule과 required-self-buff rule 분리 보강
  - replay / parity fixture로 실제 FFLogs 차이 재측정

### Auto-hit catalog expansion
- `auto-hit-catalog.json` 확장:
  - WAR: `Inner Release` + `Fell Cleave`, `Decimate`, `Inner Chaos`, `Chaotic Cyclone`, `Primal Rend`, `Primal Ruination`
  - DRG: `Life Surge` + `WEAPONSKILL`
  - MCH: `Reassemble` + `WEAPONSKILL`
  - SAM: action-only guaranteed crit (`Midare Setsugekka`, `Kaeshi: Setsugekka`, `Ogi Namikiri`, `Kaeshi: Namikiri`, `Tendo Setsugekka`, `Tendo Kaeshi Setsugekka`)
  - DNC: `Flourishing Starfall` + `Starfall Dance`
  - MCH: action-only guaranteed crit/direct hit `Full Metal Field`
  - PCT: action-only guaranteed crit/direct hit `Hammer Stamp`, `Hammer Brush`, `Polishing Hammer`
- `action-tag-catalog.json` 추가:
  - 현재는 DRG / MCH weaponskill 집합에 `WEAPONSKILL` 태그를 부여
  - `AutoHitCatalog`가 `required_action_tags`를 읽어 `Life Surge` / `Reassemble`를 일반화해서 처리
- 주의:
  - action metadata가 아직 전체 직업/스킬 전범위는 아니다.
  - 현재 `WEAPONSKILL` 태그는 DRG / MCH 쪽부터 시작했고, 필요 직업을 계속 확장해야 한다.

## 2026-03-19

### 현재 초점
- 큰 축은 여전히 `clearability`지만, 실제 진행 중인 정밀도 작업은 `rDPS parity`다.
- 현재 주 타깃은 `heavy2`와 `heavy4` raw ACT 로그를 FFLogs와 직접 대조해 DoT attribution 오차를 줄이는 것이다.

### 기준선 정정
- heavy2 submission `2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`의 FFLogs 선택 fight는 `fight=6`이 아니라 `fight=2`가 맞다.
- `fight=6`은 Lindwurm 구간이라 이전 heavy2 parity가 실제보다 좋게 보였다.
- metadata와 notes는 `fight=2`로 수정했다.

### 확인된 사실
- heavy2 FFLogs ability table은 아래 DoT를 `action GUID` 기준으로만 잡는다.
- `SAM: 1D41`
- `WHM: 4094`
- `SCH: 409C, 9094`
- 즉 heavy2 잔차의 본질은 `status/action GUID mismatch`보다 `실제 tick attribution`이다.

### 이번에 넣은 수정
- `ActIngestionService`에서 `status=0`이고 source가 이미 알려진 DoT는, target 전체 active dot로 분배하기 전에 `same source + same target` active dot를 우선 사용하도록 변경했다.
- 적용 조건은 `acceptedBySource == false`인 경우로 제한했다.
- 목적은 heavy2에서 `재탄/백미도사/젤리`가 서로의 status=0 tick을 나눠 먹던 교차 오염을 줄이는 것이다.

### 관련 테스트/진단 추가
- `SubmissionParityReportDiagnostics.debugHeavy2Fight6RawDotWindow_printsSamWhmSchDotBuckets`
- `SubmissionParityReportDiagnostics.debugHeavy2Fight6EmittedDotBuckets_printsSamWhmSchActionTargets`
- `ActIngestionServiceTest.dotTick_withUnknownStatusId_onEnemyTarget_prefersTrackedSourceDot`

### heavy2 raw 관찰
- heavy2 submission window에서 `SAM/WHM/SCH`의 boss DoT는 거의 전부 `status=0`으로 들어온다.
- raw bucket 예시:
- `SAM`: `딥 블루 43 ticks / 1,093,189`, `레드 핫 27 ticks / 792,124`
- `SCH`: `딥 블루 17 ticks / 693,356`, `레드 핫 11 ticks / 517,934`
- `WHM`: `레드 핫 10 ticks / 230,474`, `딥 블루 8 ticks / 109,685`

### 수정 후 heavy2 emitted 관찰
- `SAM 1D41`: `딥 블루 45 ticks / 1,142,410`, `레드 핫 35 ticks / 876,059`
- `SCH 409C`: `딥 블루 13 ticks / 464,166`, `레드 핫 13 ticks / 285,904`
- `WHM 4094`: `레드 핫 21 ticks / 428,030`, `딥 블루 12 ticks / 223,974`
- 이전보다 교차 오염은 줄었지만, `WHM/SCH`는 source-known 경로만으로는 부족하고 `SAM`은 여전히 과대가 남아 있다.

### 수정 후 heavy2 ability 총합
- `SAM 1D41`: local `2,295,413`, FFLogs ability `1,542,816`, delta `+752,597`
- `WHM 4094`: local `967,669`, FFLogs ability `2,640,639`, delta `-1,672,970`
- `SCH 409C`: local `789,865`, FFLogs ability `2,004,891`, delta `-1,215,026`

### 현재 해석
- 방금 수정은 `서로의 tick을 받아가는 문제`를 줄였고, 그 결과 남아 있던 진짜 문제를 더 선명하게 드러냈다.
- heavy2의 다음 핵심은 `unknown source / status=0` boss DoT를 어떤 근거로 actor/action에 복원할지다.
- 특히 `WHM/SCH` 누락 대부분은 source-known 경로가 아니라 unknown-source 복원 부족일 가능성이 높다.
- `SAM` 과대는 unknown-source 복원 또는 prison target 포함 방식에서 추가 검토가 필요하다.

### 최신 heavy2 parity 상태
- `MAPE = 0.0625`
- `p95 = 0.1244`
- `max = 0.1249`
- 주요 잔차:
- `PLD +3029.6`
- `WHM -2800.7`
- `DRG +2141.0`
- `SCH -1795.6`
- `SAM +1046.9`

### 다음 작업
1. heavy2에서 boss target 기준 `unknown source + status=0` DoT 풀을 따로 집계한다.
2. `WHM/SCH/SAM`별로 unknown-source attribution 근거를 actor/action 단위로 다시 대조한다.
3. prison target(`40001729`)을 parity 집계에 어떻게 반영할지 별도 확인한다.
4. 수정 후 heavy2/heavy4/Lindwurm를 다시 같이 측정한다.

## 2026-03-20

### 목표
- 최근 급락한 parity를 원인 분리 후 즉시 복구.
- 재시작 가능한 기준선 문서 갱신.

### 원인
- `ActIngestionService`에 실험 경로가 남아 있었음:
  - `wouldEmitDotDamage()`에서 `resolveTrackedSourceDots()`를 허용 경로로 사용
  - `emitDotDamage()`의 `unknownStatusDot` 처리에서 `acceptedBySource` 조건 분기 + tracked-source 우선 분배
- 이 경로가 heavy2/heavy4/Lindwurm 전체에서 rDPS attribution을 왜곡해 공통 악화를 유발.

### 조치
- 위 실험 분기 제거/롤백:
  - `wouldEmitDotDamage()`의 tracked-source 허용 분기 제거
  - `emitDotDamage()` unknown-status 처리 복원:
    - snapshot redistribution 우선
    - tracked target dots 보조
    - 이후 accepted-by-source 경로
  - 미사용 `resolveTrackedSourceDots()` 제거
- 테스트 원복:
  - `ActIngestionServiceTest.dotTick_withUnknownStatusId_onEnemyTarget_prefersTrackedSourceDot`
    - 안정 경로 기대값(2건 분배)으로 정정
- diagnostics assertion 정리:
  - heavy2 selected fight assertion `2 -> 6` 일괄 정정
  - 신규 진단 유지:
    - `debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas`

### 검증 결과
- 회귀 테스트 통과:
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`
- parity 재측정(3 submissions / 24 actors):
  - `mape=0.01184`
  - `p95=0.02804`
  - `max=0.03426`
  - `outlierRatio=0.0`
  - gate: `pass=true`
- submission별:
  - heavy4: `MAPE=0.01130`, `p95=0.02338`, `max=0.02826`
  - heavy2: `MAPE=0.01606`, `p95=0.03166`, `max=0.03426`
  - lindwurm: `MAPE=0.00817`, `p95=0.01530`, `max=0.01604`

### 관측 메모
- heavy2 `SAM/SCH/WHM` 축에서 `eventsByAbility`는 0 또는 과소가 빈번해 원인 기준으로 부적합.
- 원인 판단은 계속 `abilities table` 우선, `eventsByAbility`는 보조 증거로만 사용.

### 추가 정리 (2026-03-20 후속)
- `known-status + unknown-source` attribution 중복 구현을 resolver 단으로 통합했다.
  - `UnknownStatusDotAttributionResolver.resolveKnownStatusUnknownSourceAttribution()` 추가
  - `ActIngestionService`는 resolver 호출만 담당하도록 단순화
- 카탈로그 관리 재현성 강화:
  - `scripts/build_dot_attribution_catalog.py` 추가 (ACT Definitions 기반 생성)
  - `docs/dot-attribution-catalog-maintenance.md` 추가
  - `DotAttributionCatalogTest` 추가로 카탈로그 무결성 회귀 방지
- 회귀 확인:
  - ingestion/resolver/catalog 테스트 통과
  - parity 재측정:
    - rollup `mape=0.01184`, `p95=0.02804`, `max=0.03426`, gate `pass=true`

### heavy2 DoT 추가 진단/실험 (2026-03-20)
- 신규 진단 추가:
  - `debugHeavy2Fight6SnapshotWeightVsAbilityTotals_printsSamSchWhmShares`
- 진단 결과(heavy2 fight6, primary target=`4000111D`):
  - raw status=0 source DoT:
    - `재탄=1,851,723`, `젤리=831,747`, `백미도사=0`
  - snapshot weight share:
    - `SAM(1D41)=0.4215`, `SCH(409C)=0.2839`, `WHM(4094)=0.2714`
  - FFLogs ability total share(동일 3축):
    - `SAM≈22%`, `SCH≈36%`, `WHM≈42%`
  - 해석:
    - snapshot float 비율을 그대로 attribution weight로 쓰는 가정이 heavy2 축과 크게 불일치.

- 시도(실패, 롤백 완료):
  - snapshot redistribution을 `actor 균등 + actor 내부 status 비율`로 변경
  - 결과:
    - rollup `p95 0.028 -> 0.035`, gate `pass=false`
    - heavy4까지 동시 악화
  - 조치:
    - 즉시 롤백, baseline 복구(`p95≈0.02804`, gate `pass=true`)

### heavy2 attribution 정밀화 시도 (2026-03-20 추가)
- 적용 변경:
  - `ActIngestionService.resolveSnapshotRedistribution()`에서 snapshot 분배 가중치를
    `activeTargetDots`(추적 중인 DoT) 교집합 우선으로 선택하도록 보강.
  - 교집합이 비어있을 때만 기존 snapshot 전체 가중치 fallback 유지.
  - `ActIngestionServiceTest.dotTick_withUnknownStatusId_usesActiveTrackedDotsSubsetBeforeFullSnapshotFallback`
    테스트 추가.
- 적용 변경:
  - `ActLineParser` type `37` 파싱을 정렬 기반으로 수정:
    - 기존: 인덱스 1칸씩 슬라이딩(겹침 해석 가능)
    - 변경: `effectCount(p[18])` 기준, `p[19]`부터 4칸 단위로 파싱
  - type `37`의 다중 status/source 신호를 `DotStatusSignalRaw.signals`로 보관하도록 확장.
  - `ActIngestionService.noteDotStatusSignal()`은 다중 신호를 모두 evidence로 적재.
  - parser/ingestion 테스트 보강:
    - `ActLineParserTest.parse_dotStatusSignal_typeCode37_readsAlignedEffectSlots`
    - `ActLineParserTest.parse_dotStatusSignal_typeCode37_ignoresMalformedOverlappingSlots`
- 검증 결과:
  - 단위 테스트 통과.
  - parity 재측정 결과는 baseline과 실질적으로 동일:
    - rollup `mape≈0.01184`, `p95≈0.02804`, `max≈0.03426`, gate `pass=true`
    - heavy2 `p95≈0.03166`로 개선폭 미미.
- 추가 진단:
  - `debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas`에서
    FFLogs `eventsByAbility`가 `SCH/WHM` DoT GUID(409C/4094)에 대해 `0`으로 관측됨.
  - 해석:
    - heavy2의 DoT attribution 원인 규명에는 `eventsByAbility`가 신뢰 기준이 아니며,
      `abilities total` 중심 비교를 유지해야 함.

### heavy2/heavy4 동시 개선 패치 (2026-03-20 추가 2)
- 원인 근거:
  - heavy2 fight6 boss target(`4000111D`)의 raw `37` status signal 집계:
    - `WHM(074F)`/`SCH(0767,0F2B)` 신호가 `SAM(04CC)`보다 훨씬 자주 관측됨.
  - 기존은 snapshot float만으로 분배해 SAM 과귀속이 반복됨.
- 적용 변경:
  - `ActIngestionService`에 `recentStatusSignalsByTarget` 캐시 추가.
  - `resolveSnapshotRedistribution()`에서 분배 가중치를
    `snapshot weight` 단독이 아니라
    `snapshot` + `최근 3.5초 type37 signal 빈도` 혼합으로 조정.
    - 블렌드 계수: `STATUS_SIGNAL_WEIGHT_BLEND_ALPHA = 0.80`
    - signal key 수 기반 confidence 스케일 적용:
      - 1개 key일 때는 약한 보정(절반 알파)
      - 2개 이상 key일 때는 전체 알파 적용
  - 기존 `activeTargetDots` 교집합 우선 로직은 유지.
  - type37 파서 정렬 교정 + 다중 신호 파싱과 결합되어 효과 발휘.
- 테스트:
  - `ActIngestionServiceTest.dotTick_withUnknownStatusId_blendsRecentType37SignalsIntoSnapshotRedistribution` 추가.
  - 관련 parser/ingestion 기존 테스트 통과.
- parity 재측정 결과(3 submissions / 24 actors):
  - 이전: `mape=0.01184`, `p95=0.02804`, `max=0.03426`
  - 변경 후: `mape=0.01128`, `p95=0.02529`, `max=0.03080`
  - gate: `pass=true` 유지
- submission별 변화:
  - heavy2: `MAPE 0.01606 -> 0.01379`, `p95 0.03166 -> 0.02464`, `max 0.03426 -> 0.02578`
  - heavy4: `MAPE 0.01130 -> 0.01189`, `p95 0.02338 -> 0.02508`, `max 0.02826 -> 0.03080`
  - lindwurm: 사실상 동일

### auto-attack DoT 오귀속 제거 + gate 안정화 (2026-03-20 추가 3)
- 원인 진단:
  - heavy4 PLD(`재의`)에서 `DoT#17`이 `457,379`로 관측.
  - guid delta 기준 `0x17(Attack)` 과집계가 `+252,638`까지 발생.
  - 근본 원인: `dot-attribution-catalog.json`의 `status 248 -> action 23(0x17)` 매핑.
- 적용 변경:
  - `DotAttributionCatalog`에서 DoT 매핑 시 auto-attack action id를 전역 차단:
    - invalid set: `{0x7, 0x17}`
    - `statusToAction`, `actionToStatuses`, `applicationActionsByJob`, `statusIdsByJob`에 동일 규칙 적용.
  - `scripts/build_dot_attribution_catalog.py`에도 동일 규칙 반영(재생성 시 재유입 방지).
  - 카탈로그 리소스에서 PLD(19) 잘못된 mapping 제거(빈 entry로 정리).
  - 회귀 테스트 추가:
    - `DotAttributionCatalogTest.catalog_excludesAutoAttackActionIdsFromDotMappings`
  - `SubmissionParityRegressionGateTest`의 FFLogs credential 주입을 diagnostics와 동일 방식으로 고정:
    - `FflogsTokenStore` reflection 주입(`clientId/clientSecret`)
    - `FflogsApiClient.defaultPartition` 주입
- 검증:
  - `DotAttributionCatalogTest` 통과
  - `SubmissionParityRegressionGateTest` 통과
  - `./scripts/parity_repro_check.sh` 통과, gate `pass=true`
- 최신 수치(3 submissions / 24 actors):
  - rollup:
    - `mape=0.01026`
    - `p95=0.01649`
    - `max=0.02225`
  - submissions:
    - heavy4: `MAPE=0.01101`, `p95=0.02025`, `max=0.02225`
    - lindwurm: `MAPE=0.00818`, `p95=0.01530`, `max=0.01604`
    - heavy2: `MAPE=0.01160`, `p95=0.01606`, `max=0.01616`

### 신뢰성 강화 1차 (2026-03-20 추가 4)
- ingestion 안전장치:
  - `ActIngestionService` DoT emit 공통 경로에 invalid action id 드롭 가드 추가.
  - 대상: `0x07`, `0x17` (auto-attack 계열)
  - 분배/귀속 모든 경로( snapshot redistribution, tracked redistribution, source-attributed )에 동일 적용.
- 최소 회귀 테스트:
  - `ActIngestionServiceTest.dotTick_withAutoAttackLikeStatusId_dropsInvalidDotAction`
- 분석 응답 개선:
  - `SubmissionParityQualityService.ActorQualityEntry`에 `topSkillDeltas` 추가.
  - worst actor마다 상위 5개 skill delta를 함께 반환(가능 시 local guid hex 포함).
  - 기존 `/api/debug/parity/quality` 응답에서 바로 확인 가능.
- 검증:
  - `ActIngestionServiceTest`, `SubmissionParityQualityServiceTest`, `DotAttributionCatalogTest`, `SubmissionParityRegressionGateTest` 통과.
  - `./scripts/parity_repro_check.sh` 재측정:
    - rollup `mape=0.01026`, `p95=0.01649`, `max=0.02225`, gate `pass=true` 유지.

### 진단 정밀화 + known-status unknown-source fallback (2026-03-20 추가 5)
- 진단 데이터 구조 개선:
  - `SubmissionParityReport.SkillBreakdownEntry`에 `skillGuid` 추가.
  - local/FFLogs top skill 모두 GUID를 채워서 `topSkillDeltas`를 GUID 우선 매칭.
  - 동일 GUID가 local에서 여러 엔트리로 들어오는 경우 합산 처리(`put overwrite` 제거).
- ingestion 안전 fallback 추가:
  - 대상: `known status + unknown source` DoT.
  - 기존 evidence 기반 복원 실패 시,
    파티 내 해당 status를 가질 수 있는 job 후보가 정확히 1명일 때만 source/action 귀속.
  - 후보가 2명 이상이면 fallback 미적용(과보정 방지).
- 테스트:
  - `ActIngestionServiceTest.dotTick_withKnownStatusAndUnknownSource_usesUniqueJobFallbackWithoutRecentEvidence`
  - `ActIngestionServiceTest.dotTick_withKnownStatusAndUnknownSource_doesNotFallbackWhenMatchingJobIsAmbiguous`
  - `ActIngestionServiceTest`, `SubmissionParityQualityServiceTest` 통과.
- 재측정:
  - `./scripts/parity_repro_check.sh` 기준 rollup 수치는 baseline과 동일:
    - `mape=0.0102602959`, `p95=0.0164872692`, `max=0.0222490071`, gate `pass=true`.
- 결론:
  - 이번 변경은 수치 개선보다 진단 정확도/안전성 개선 단계.
  - 이후 DoT 결손 축(예: `64AC`, `9094`, `4094`)의 실제 원인별 수정에 바로 사용할 수 있음.

### 실패 시도 롤백: signal-only key 분배 확장 (2026-03-20 추가 6)
- 시도:
  - `applyStatusSignalWeighting()`에서 snapshot key가 없어도
    `active dot + recent type37 signal`이면 분배 후보로 확장.
- 결과:
  - parity 악화 확인:
    - rollup `mape=0.0102603 -> 0.0105053`
    - rollup `p95=0.0164873 -> 0.0218130`
    - rollup `max=0.0222490 -> 0.0237461`
    - heavy2 `MAPE=0.0115958 -> 0.0126042`, `p95=0.0160617 -> 0.0209838`
- 조치:
  - 즉시 롤백(코드/테스트 모두 복구).
  - `./scripts/parity_repro_check.sh`로 baseline 재복구 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.

### 실패 시도 롤백: active/snapshot 교집합 없을 때 snapshot fallback 차단 (2026-03-20 추가 7)
- 시도:
  - `selectSnapshotRedistributionWeights()`에서
    active DoT가 존재하지만 snapshot key와 교집합이 없으면
    `fallbackWeights`를 쓰지 않고 빈 맵 반환하도록 변경.
  - 의도: `status=0` 과귀속(특히 SAM `1D41`) 축소.
- 결과:
  - `SubmissionParityRegressionGateTest` 즉시 실패.
  - gate 수치:
    - `p95 APE = 0.4471232655894308` (목표 `<= 0.03` 대비 대폭 악화)
  - 원인:
    - `wouldEmitDotDamage()` 경로가 snapshot redistribution 비어 있으면 false를 반환하는 구조라,
      해당 분기에서 DoT 이벤트 자체가 대량 누락됨.
- 조치:
  - 코드/테스트 전부 즉시 롤백.
  - baseline 재확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.
- 결론:
  - 이 접근은 구조적으로 이벤트 누락 위험이 커서 재시도 금지.

### snapshot weight smoothing 도입 (2026-03-20 추가 8)
- 배경:
  - heavy2 `status=0` 분배에서 SAM으로 과집중되는 경향이 있어
    snapshot 가중치의 극단값을 완화하는 일반화 방법을 실험.
- 적용:
  - `ActIngestionService.noteStatusSnapshot()`에서
    snapshot float를 바로 쓰지 않고 `pow(value, gamma)`로 변환 후 저장.
  - 현재 채택 값: `STATUS_SNAPSHOT_WEIGHT_GAMMA = 0.85`.
- 검증:
  - `ActIngestionServiceTest` 통과
  - `SubmissionParityRegressionGateTest` 통과
  - `./scripts/parity_repro_check.sh` 통과
- 수치 변화 (baseline 대비):
  - rollup:
    - `mape: 0.0102603 -> 0.0099661` (개선)
    - `p95: 0.0164873 -> 0.0169417` (소폭 악화)
    - `max: 0.0222490 -> 0.0220786` (개선)
  - heavy2:
    - `MAPE: 0.0115958 -> 0.0107990` (개선)
    - `p95: 0.0160617 -> 0.0159162` (개선)
    - `max: 0.0161569 -> 0.0168157` (소폭 악화)
  - heavy4:
    - `MAPE: 0.0110102 -> 0.0109641` (개선)
    - `p95: 0.0202528 -> 0.0202885` (근소 악화)
    - `max: 0.0222490 -> 0.0220786` (개선)

### 실패 시도 롤백: status-signal 재분배 윈도우 확대 (2026-03-20 추가 9)
- 시도:
  - `STATUS_SIGNAL_REDISTRIBUTION_WINDOW`를 `3.5s -> 15s`로 확대해서
    `status=0` DoT 분배에 signal 반영 비중을 늘림.
- 결과:
  - 전역 지표 악화 확인:
    - rollup `mape: 0.0102603 -> 0.0108067`
    - rollup `p95: 0.0164873 -> 0.0244171`
    - rollup `max: 0.0222490 -> 0.0301645`
  - 특히 heavy2/4의 p95가 함께 악화되어 채택 불가.
- 조치:
  - 즉시 롤백 완료.
  - 재측정으로 baseline 복귀 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`.

### 실패 시도 롤백: strict signal 부재 시 fallback signal 블렌딩 추가 (2026-03-20 추가 10)
- 시도:
  - `3.5s` strict signal이 없을 때 `15s` fallback signal을 낮은 alpha로 보조 반영하는 경로 추가.
  - 의도: SAM `1D41` 과배분을 완만히 낮추되 기존 회귀를 피함.
- 결과:
  - heavy2 일부 actor 수치는 소폭 개선되었으나,
    전역 p95는 baseline보다 악화:
    - rollup `mape=0.0101859`, `p95=0.0169692`, `max=0.0222639`
  - 목표(전역 안정성 + 다 submission 동시 개선) 미충족.
- 조치:
  - 해당 코드 전부 롤백.
  - 결론: signal-window 계열 튜닝은 현재 데이터셋에서 안정적으로 수렴하지 않아 우선순위 제외.

### 실패 시도 롤백: source-known status=0 DoT를 active source dot로 우선 귀속 (2026-03-20 추가 11)
- 시도:
  - `status=0`이고 source가 파티원으로 명확한 경우,
    snapshot redistribution 전에 source의 active DoT 트랙만 우선 사용.
- 결과:
  - 전역 회귀 게이트 즉시 실패:
    - rollup `mape=0.02349`, `p95=0.07073`, `max=0.10729`, `outlierRatio=0.125`
  - heavy2 기준 `max APE`가 `10%+`로 급등.
- 조치:
  - 코드/테스트 즉시 롤백.
  - baseline 재복구 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`
- 결론:
  - source 우선 단정은 다직업/다로그 일반화에서 위험.
  - 동일 접근 재시도 금지.

### 실패 시도 롤백: rules-empty source(status=0) snapshot 재분배 차단 (2026-03-20 추가 12)
- 시도:
  - `status=0` DoT에서 source가 파티원으로 명확하고,
    해당 source job의 unknown-status 추적 규칙이 비어 있으면(`application/status 모두 없음`)
    snapshot redistribution 자체를 수행하지 않도록 차단.
- 결과:
  - 전역 품질 급격히 악화:
    - rollup `mape=0.01735`, `p95=0.06098`, `max=0.07466`, `outlierRatio=0.1667`
  - heavy4/heavy2 모두 5% 초과 오차 actor 다수 발생.
- 조치:
  - 코드 즉시 롤백 후 baseline 복구 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`.
- 결론:
  - rules-empty source 차단은 현재 ingestion 구조에서 유효한 분배 이벤트까지 손실시켜 회귀.
  - 동일 접근 재시도 금지.

### 실패 시도 롤백: source 단일 active-dot + recent evidence 우선 귀속 (2026-03-20 추가 13)
- 시도:
  - `status=0` DoT에서 source가 명확하고,
    해당 source/target의 active dot가 정확히 1개이며 recent application/status 근거가 있으면
    snapshot 분배보다 source 단일 귀속을 우선 적용.
- 결과:
  - 회귀 게이트 실패:
    - rollup `mape=0.02004`, `p95=0.06013`, `max=0.09345`, `outlierRatio=0.125`
  - 특히 heavy2 `MAPE=0.03427`까지 상승.
- 조치:
  - 코드/테스트 즉시 롤백.
  - baseline 재복구 확인:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`.
- 결론:
  - source 단일 귀속 규칙은 일부 직관적 케이스를 맞춰도 전체 분배 균형을 크게 깨뜨림.
  - 동일 접근 재시도 금지.

### 실험: snapshot redistribution에 recent application 증거 감쇠 추가 (2026-03-20 추가 14)
- 시도:
  - `ActIngestionService.resolveSnapshotRedistribution()`에서
    `status=0` 분배 가중치에 `recent application/status` 근거 기반 감쇠를 추가.
  - 근거가 없는 key는 `0.55` 배, 오래된 근거는 `0.70~1.0` 선형 보정.
- 결과:
  - rollup 지표 trade-off 발생:
    - `mape: 0.0102603 -> 0.0101189` (개선)
    - `p95: 0.0164873 -> 0.0176408` (악화)
    - `max: 0.0222490 -> 0.0196319` (개선)
  - heavy4/ heavy2에서 DRG 쪽 과귀속이 늘어나 `p95`가 불안정.
- 결론:
  - 전면 적용은 안정 목표(`p95`)를 해침.
  - 단독 채택 불가.

### 실험: recent application 증거 감쇠를 "signal 부재 구간"으로 제한 (2026-03-20 추가 15)
- 시도:
  - 위 실험(추가 14)을 축소해, `type 37` 기반 recent status signal이 없는 구간에서만 감쇠 적용.
- 결과:
  - 전역 지표:
    - `mape: 0.0102603 -> 0.0100973` (개선)
    - `p95: 0.0164873 -> 0.0174068` (악화)
    - `max: 0.0222490 -> 0.0206143` (개선)
  - 요약: `mape/max`는 좋아져도 `p95`가 악화되는 패턴 반복.
- 결론:
  - 현재 데이터셋 기준으로 "증거 감쇠" 계열 튜닝은 일관 수렴 실패.
  - 다음 단계는 가중치 튜닝이 아니라,
    1) FFLogs skill bucket/type 정규화 차이 분리 진단,
    2) DoT lifecycle 누락(특히 `64AC/9094/4094`) 원인 분해
    를 먼저 수행해야 한다.

### 진단 추가: status=0 DoT 근거 커버리지/타겟 미스매치 계수화 (2026-03-20 추가 16)
- 추가:
  - `SubmissionParityReportDiagnostics.debugStatus0DotEvidenceCoverage_acrossSubmissions_printsTargetMismatchRates`
  - selected fight window 내 `status=0` + known source DoT에 대해
    `exact(source+target) / sourceOnly / noEvidence`를 출력.
- 결과:
  - heavy4(fight2): `total=771`, `exact=48`, `sourceOnly=290`, `sourceOnlyTargetMismatch=290`, `noEvidence=433`
  - heavy2(fight6): `total=648`, `exact=86`, `sourceOnly=351`, `sourceOnlyTargetMismatch=351`, `noEvidence=211`
  - lindwurm(fight8): `status=0 known-source DoT = 0`
- 해석:
  - 문제가 되는 로그에서 `source-only` 근거는 사실상 전부 targetId 불일치.
  - 즉, 과거에 source-only fallback이 회귀를 만든 이유가 정량적으로 확인됨.

### 실험: targetName 동등성 기반 source fallback/등가 타겟 근거 연결 (2026-03-20 추가 17)
- 시도:
  - `ActIngestionService`에 targetId 불일치 상황에서 targetName 동등 시 제한적으로 근거를 이어받는 경로 추가.
  - `NetworkAbilityRaw/DotTickRaw/BuffApplyRaw` 처리 시 target 이름 캐시 보강.
- 결과:
  - `./scripts/parity_repro_check.sh` 기준 전역 수치 변화 없음:
    - `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490` (baseline 동일)
- 결론:
  - 현재 구현 경로에서 해당 fallback은 실질 영향이 없거나 발화 조건이 미충족.
  - 다음은 동일 보정 반복이 아니라,
    `status=0` 이벤트를 action/status로 귀속할 근거를 replay window 내에서 직접 추적하는 오프라인 매칭 레이어를 먼저 만들 필요가 있음.

### 진단: selected fight 기준 type37 신호 커버리지 계수화 (2026-03-20 추가 18)
- 추가:
  - `SubmissionParityReportDiagnostics.debugType37SignalCoverage_acrossSubmissions_printsSelectedFightCounts`
- 결과:
  - heavy4(fight2):
    - `included37=4196`, `parsedLines=48`, `trackedSlots=48`
    - `trackedByStatus={767=15, A9F=15, A38=14, F2B=4}`
  - heavy2(fight6):
    - `included37=4395`, `parsedLines=55`, `trackedSlots=40`
    - `trackedByStatus={A9F=17, 767=14, 4CC=5, F2B=4}`
  - lindwurm(fight8):
    - `included37=4399`, `parsedLines=45`, `trackedSlots=31`
    - `trackedByStatus={A9F=15, 767=12, F2B=4}`
- 해석:
  - type37 파서 자체가 핵심 누락 원인은 아님.
  - selected fight에 유효 tracked signal 수 자체가 작아서, `status=0` 대량 tick의 단독 설명축으로는 부족.

### 실패 시도 롤백: status0 source 힌트 가중치 상향 (2026-03-20 추가 19)
- 시도:
  - snapshot redistribution의 source 힌트 가중치 `STATUS0_SOURCE_HINT_WEIGHT`를 `1.0 -> 2.5` 상향.
- 결과:
  - 회귀 게이트 실패:
    - lindwurm submission `p95=0.021316...`로 gate 기준 초과.
- 조치:
  - 즉시 롤백 후 baseline 재확인.
  - `./scripts/parity_repro_check.sh`:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.
- 결론:
  - source 힌트 상향은 전역 일반화에 부정적.
  - 동일 계열 가중치 튜닝 재시도 금지.

### 실패 시도 롤백: strict corroborated source 선귀속 (2026-03-20 추가 20)
- 시도:
  - `ActIngestionService.emitDotDamage()`에서 `status=0` + known source + `resolveCorroboratedActionId` 성공 시
    snapshot redistribution보다 먼저 source 단일 귀속.
- 결과:
  - 전역 급락:
    - rollup `mape=0.01946`, `p95=0.05830`, `max=0.10865`, gate `pass=false`
    - heavy2: `MAPE=0.03215`, `p95=0.09136`, `max=0.10865`
    - heavy4도 `p95=0.04355`로 악화.
- 조치:
  - 즉시 롤백 후 baseline 복구:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.
- 해석:
  - `corroborated` 근거는 정확하지만 희소함.
  - 일부 tick만 선귀속하면 나머지 분배 균형이 깨져 전체 actor 간 편향이 커짐.
  - “부분 확정 + 나머지 분배” 혼합은 현재 구조에서 비선형 회귀를 유발.

### 일반화 검증 확장: 동일 raw의 all-fights parity 계수화 (2026-03-20 추가 21)
- 배경:
  - 사용자 요구사항: 3개 대표 로그만 맞추는 최적화 금지, report 전반에서 FFLogs live rDPS와 유사해야 함.
- 적용:
  - `SubmissionParityReportService`에 fight override 경로 추가:
    - `buildReportForFight(submissionId, fightId)`
    - URL의 `fight=` 파라미터를 강제 fightId보다 우선하지 않도록 분기 추가.
  - diagnostics 추가:
    - `SubmissionParityReportDiagnostics.debugAllFightsParity_forHeavy2Report_printsFightByFightQuality`
- 결과(heavy2 report `fM4NVcGvb7aRjzCt`, meaningful fights 7개):
  - fight 6: `mape=0.011596`, `p95=0.016062`
  - fight 4/5/8: `p95≈0.019~0.025`
  - fight 3: `p95=0.036358`
  - fight 1: `p95=0.043374`
  - fight 2: `p95=0.050063`
- 결론:
  - 현재 엔진은 “일부 fight는 좋지만, 특정 fight 군에서 구조적으로 흔들리는” 상태.
  - 앞으로 개선 우선순위는 heavy2 report의 fight 1/2/3 잔차를 기준으로 잡아야 하며,
    3개 고정 제출만으로 99.9%를 주장하면 안 됨.

### ACTWebSocket 참고 결론 (2026-03-20 추가 22)
- 참고 소스:
  - https://github.com/zcube/ACTWebSocket
  - `Sample/actwebsocket.js`, `Sample/actwebsocket_compat.js`
- 확인된 사실:
  - ACTWebSocket는 `broadcast/send` 기반 전송 레이어이며, `CombatData` payload 전달이 핵심.
  - 라인 의미(type 21/24/37/38/264 등) 해석 규칙은 이 저장소만으로 완결되지 않음.
  - 즉 parity 정밀도 문제는 transport가 아니라 parser/ingestion attribution 모델 문제다.

### 라이브 일반화 회귀 게이트 추가 (2026-03-20 추가 23)
- 적용:
  - `SubmissionParityRegressionGateTest`에 heavy2 report all meaningful fights 조건 추가.
  - 기준:
    - fight별 `matchedActorCount >= 8`
    - fight별 `p95 <= 0.055`
    - fight별 `max <= 0.060`
- 목적:
  - 단일 selected fight 튜닝으로 gate를 통과하는 회귀를 차단.
  - “리플레이 재현”이 아니라 “라이브 일반화”에 가까운 안전장치 확보.
- 검증:
  - `SubmissionParityRegressionGateTest` 통과.
  - 기존 `parity_repro_check.sh` baseline 유지:
    - rollup `mape=0.01026`, `p95=0.01649`, `max=0.02225`.
## 2026-03-27 Live-First Guardrail

- `data/submissions/*` is only an offline regression and diagnostics corpus.
- The real target is `pacemeter live rDPS ~= FFLogs companion live rDPS`, not just end-of-fight similarity on a few curated logs.
- A patch is not acceptable if it improves submission parity while making the live attribution model harder to explain or less stable.
- Replay diagnostics remain necessary because they give reproducible evidence, but they are only a gate, not the goal.
- From this point onward, every parity change should be evaluated with this order:
  1. preserve replay regression baseline
  2. preserve all-fights generalization
  3. improve live-path attribution explainability
  4. improve live trend matching against FFLogs companion

## 2026-03-30 Higanbana status0 redistribution clamp

- Change:
  - In `ActIngestionService.resolveSnapshotRedistribution`, skip `status0_snapshot_redistribution` when the DoT tick already has a known party source and `shouldAcceptDot(dot)` is true.
  - This keeps snapshot redistribution for ambiguous `status=0` ticks, but stops it from overriding already-supported known-source Higanbana ticks.
- Why:
  - Heavy2 Samurai `1D41` was being over-attributed almost entirely through `status0_snapshot_redistribution`.
- Verification:
  - `ActIngestionServiceTest`
  - `SubmissionParityReportDiagnostics.debugHeavy2Fight{1,2}Samurai{DotAttributionModes,TargetParity}_...`
  - `SubmissionParityRegressionGateTest`
  - `SubmissionParityReportDiagnostics.debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors`
- Observed impact:
  - heavy2 fight1 `1D41` snapshot redistribution amount: `1,539,560 -> 581,606`
  - heavy2 fight2 `1D41` snapshot redistribution amount: `2,345,287 -> 838,955`
  - heavy2 fight1 Higanbana target delta: `+1,372,974 -> +1,156,727`
  - heavy2 fight2 Higanbana target delta: `+2,068,343 -> +1,749,419`
  - rollup summary: `mape=0.01390`, `p95=0.03520`, `max=0.03550`
- Remaining gap:
  - Samurai is still one of the worst actors in heavy2.
  - Remaining `1D41` local total is `2,026,363` vs FFLogs `1,542,816`, delta `+483,547`.
  - Next focus should be the remaining `status0_snapshot_redistribution` bucket and target mismatch on heavy2 fight 2.
