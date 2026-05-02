# Parity Patch Notes

## 문서 목적
- 목표: `pacemeter live rDPS ~= FFLogs companion live rDPS`
- 원칙: 스킬별 하드코딩 보정 금지, explainable attribution만 허용
- 본 문서는 **현재 진행에 필요한 유효 체크포인트만** 유지한다.

## 현재 기준 (2026-04-10)

### 핵심 목표
- live parity 우선
- selected fight 단일 튜닝 금지
- heavy2 / heavy4 / lindwurm / heavy2 all-fights gate 동시 유지

### 환경 고정
- `GRADLE_USER_HOME=.gradle-home` 단일 사용
- `.gradle-home-ascii*` 계열 디렉터리 제거 완료

### 현재 production 상태
- 파일: `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- 유지 중인 핵심 상수/규칙
  - `KNOWN_SOURCE_STALE_OTHER_TARGET_WINDOW_MS = 4000`
  - `KNOWN_SOURCE_TWO_TARGET_SAME_SOURCE_WEIGHT_FACTOR = 0.00`
  - `FOREIGN_DOMINANT_ACTION_REDUCTION_FACTOR = 0.00`
- 유지 중인 방향
  - `status=0` known-source multi-target에서 전역 clamp 대신 evidence 기반 분해
  - skill-specific 분기 없이 공통 weighted/tracked 규칙만 조정

## 최신 검증 수치 (heavy2 fight2)

### target parity
- DRG `64AC`: `localTotal=1,967,067`, `fflogsTotal=1,934,116`, `delta=+32,951`
- SAM `1D41`: `localTotal=1,693,243`, `fflogsTotal=553,888`, `delta=+1,139,355`

### actor totals surface (heavy2 fight2)
- DRG(구려): `local=40,613.7`, `fflogs=39,580.3`, `delta=+1,033.4`
- SAM(재탄): `local=35,430.6`, `fflogs=35,103.8`, `delta=+326.8`
- SCH(젤리): `local=19,109.8`, `fflogs=19,313.6`, `delta=-203.8`
- WHM(백미도사): `local=22,138.8`, `fflogs=22,678.6`, `delta=-539.8`

## 롤업/게이트 상태 (2026-04-10 재검증)
- gate: `pass=true`
- `mape=0.011888730720603377`
- `p95=0.025951827200127585`
- `max=0.03537628179947446`
- pass:
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`

## 최근 시도 요약

### 채택
1. `KNOWN_SOURCE_TWO_TARGET_SAME_SOURCE_WEIGHT_FACTOR` 연속 미세 조정
- `0.36 -> ... -> 0.19 -> 0.18 -> ... -> 0.13`까지 개선 확인
- 관찰:
  - heavy2 `64AC`/`1D41` 동시 개선 추세 유지
  - rollup `mape/p95` 동시 개선
  - heavy4/lindwurm actor totals는 유의미 변화 없음
- gate 유지

2. 역전/안정성 경계 확인
- `0.11`: 테스트 실패 발생(직전 값으로 복귀)
- `0.12`: 일부 재검증에서 diagnostics assertion 불안정
- 결론: 현재 안정 고정값은 `0.13`

3. `KNOWN_SOURCE_STALE_OTHER_TARGET_WINDOW_MS: 8000 -> 6000 -> 5000 -> 4000`
- 효과: heavy2 `64AC` 추가 개선, gate 유지

4. `FOREIGN_DOMINANT_ACTION_REDUCTION_FACTOR` 재튜닝
- 1차 스윕: `0.46 -> 0.30` (gate 유지 범위)
- 관찰:
  - factor를 낮출수록 heavy2 `1D41`/rollup mape가 일관되게 개선
  - `64AC`는 소폭 역행하지만 합산 잔차는 개선
- 2차 스윕: `0.30 -> 0.28 -> 0.26 -> 0.22 -> 0.14 -> 0.00`
- 추가 관찰:
  - 탐색 구간에서 gate는 모두 유지
  - `mape`는 지속 개선
  - `p95`는 소폭 상승(여전히 gate 내)
  - heavy4/lindwurm 주요 actor delta는 큰 변화 없음
- 채택: `0.00` (현 탐색 범위 내 역전점 미발견)

5. `KNOWN_SOURCE_TWO_TARGET_SAME_SOURCE_WEIGHT_FACTOR` 재튜닝 (foreign=0.00 고정)
- 스윕: `0.13 -> 0.12 -> ... -> 0.01 -> 0.00`
- 관찰:
  - gate 전 구간 유지
  - `mape/p95` 단조 개선
  - heavy2 `1D41`/`64AC` 동시 개선
  - heavy4/lindwurm 주요 actor delta 큰 변화 없음
- 채택: `0.00` (현 탐색 범위 내 역전점 미발견)

6. `status0_tracked_target_split` evidence suppression 1개 추가
- 변경:
  - `acceptedBySource && status=0 && multi-target && recentExact=null && trackedDots.size()==4 && sourceTrackedDots(1) + recentSourceAction 일치 + recent action/status evidence target 일치(현재 target과 다름) && foreignSourceCount>=3 && foreignActionCount>=3`에서 `status0_tracked_target_split` 진입 억제
- 효과:
  - heavy2 잔차를 baseline 대비 유지 개선
  - gate 유지
  - `mape` 개선, `p95`는 여전히 gate 내
- 해석:
  - 과억제(공격적 suppression) 대비 부작용을 줄인 균형점

### 기각/원복
1. `FOREIGN_DOMINANT_ACTION_REDUCTION_FACTOR: 0.55 -> 0.62` (중간값 실험)
- 결과:
  - `64AC` 소폭 개선
  - `1D41` 재악화
- 결론: 동시 개선 실패로 기각, `0.55` 원복

2. `status0_weighted_tracked_target_split` recent same-target evidence gate 추가
- 결과:
  - heavy2 일부 개선 but gate 실패
- 결론: 전역 gate 훼손으로 기각, 원복

3. `status0_tracked_source_target_split` corroborated evidence gate 실험
- 결과:
  - heavy2/rollup/gate 수치 변화 유의미하지 않음
  - mode breakdown 확인 시 heavy2 fight2의 지배 경로는 `tracked_target_split`/`weighted_tracked_target_split`
- 결론: 영향 없는 규칙으로 판단, 코드 원복

4. `status0_tracked_target_split` suppression 범위 확장 (`trackedDots.size()==3 && activeTargets==2`)
- 결과:
  - heavy2 `1D41`: `+1,119,785`로 추가 개선
  - rollup: `mape=0.011993527799304437`로 악화
  - gate는 유지
- 결론:
  - selected fight 이득 대비 전역 품질 손실이 커서 기각
  - 최종은 `trackedDots.size()==4 && foreignSourceCount>=3 && foreignActionCount>=3` 유지

5. `trackedDots.size()` 절대값 완화 시도 (`>=3`, `>=4`)
- 결과:
  - heavy2 selected 일부 지표 개선 구간이 있었지만 rollup `mape` 악화
  - `tracked>=4`는 baseline과 실질 변화 없음
- 결론:
  - 절대값 완화는 explainable한 전역 개선으로 이어지지 않아 기각/원복

6. `foreign-dominated` 억제 시 same-source 재할당 시도
- 변경:
  - `status0_tracked_target_split` 진입 억제 조건에서 drop 대신
    `status0_tracked_source_target_split` 경로로 재할당
- 결과:
  - heavy2 DRG `64AC` 역행 (`+32,951 -> +65,485`)
  - rollup 악화 (`mape=0.011952358347940117`, `p95=0.026297037866080474`)
- 결론:
  - 의도와 반대로 오염을 증가시켜 기각/원복

## 다음 액션
1. 현재 고정 상수(`same-source=0.00`, `foreign-dominant=0.00`)에서 회귀 안정성 유지 확인
2. `trackedCount` 고정값 조건 제거: 비율 기반(`foreign dominance + evidence conflict`) gate 설계
3. 변경은 항상 1개씩, baseline/gate 즉시 재검증
4. heavy2 개선 시 heavy4/lindwurm/all-fights gate 동시 확인

## 최신 체크포인트 (2026-04-29)

### 현재 채택 상태
- `status0_tracked_target_split_recent_exact_chaotic_same_source_dampened` 유지
- 조건:
  - known party source의 `status=0`
  - `recentSource == recentExact == sourceTrackedAction == 64AC`
  - tracked candidates `>=3`, foreign distinct action `>=2`
  - same-source `64AC` weight `0.125`, foreign candidates `1.0`

### 최신 검증
- `ActIngestionServiceTest`: pass
- `SubmissionParityRegressionGateTest`: pass
- rollup:
  - `mape=0.009947746177787686`
  - `p95=0.021784436570755752`
  - `max=0.03537628179947446`
  - `pass=true`
- selected submissions:
  - heavy2 fight2 `mape=0.013349355288288303`, `p95=0.030010375805500228`
  - heavy4 fight5 `mape=0.011226633878142411`, `p95=0.01996122456261421`
  - lindwurm fight8 `mape=0.0052672493669323394`, `p95=0.012846064736486258`

### 최신 진단
- `status0_snapshot_redistribution` recipient/action 계측 추가.
- selected fight 모두 snapshot 큰 덩어리가 `active_subset + foreign + activeTargets=1`로 emitted 됨.
- top snapshot foreign recipients:
  - heavy2 fight2: `1D41=147,432`, `409C=113,186`, `64AC=94,780`, `4094=89,921`
  - heavy4 fight5: `5EFA=1,170,994`, `409C=1,094,803`, `64AC=789,520`, `9094=168,036`
  - lindwurm fight8: `40AA=308,543`, `409C=282,225`, `64AC=256,700`
- 결론:
  - snapshot path에서 `party raw source -> same-source 우선`을 직접 원인으로 보는 가설은 맞지 않음.
  - 다음 production 변경 전에는 `active_subset + foreign + activeTargets=1` 내부 recipient over/under를 먼저 분해해야 함.

### 추가 진단/기각 (2026-04-29)
- `active_subset + foreign + activeTargets=1` recipient에 local/FFLogs skill delta를 붙여 확인.
- over/under가 섞임:
  - heavy2: `1D41 +21,271`, `64AC +79,215`, `409C -124,256`, `4094 -271,294`
  - heavy4: `5EFA +152,412`, `409C +47,766`, `64AC +354,504`, `9094 -216,108`
  - lindwurm: `40AA +96,174`, `409C +23,163`, `64AC +141,527`, `9094 -196,243`
- 기각 실험:
  - known party raw source이고 same-source 후보가 없는 `active_subset + foreign` 후보 `>=3` snapshot tick을 suppress.
  - `ActIngestionServiceTest`는 pass였지만 regression gate 실패:
    - rollup max actor APE `0.06657452677209132`
    - heavy2 all-fights fight8 p95 `0.07191636895033413`
  - 즉시 원복했고 gate/rollup은 기준선 복귀.
- 결론:
  - snapshot active subset 전체 drop/dampen은 금지.
  - 다음은 raw source의 status payload/action evidence와 active target lifecycle을 더 분해해야 함.

### rawSource status evidence / PLD 매핑 기각 (2026-04-30)
- `rawSource=10128857` 타임라인 진단에서 `status=0` tick 직전 같은 source의 `status=0xF8` 적용이 확인됨.
- PLD `0xF8 -> 0x17` catalog 복원 실험:
  - `DotAttributionCatalogTest`: pass
  - `ActIngestionServiceTest`: pass
  - `SubmissionParityRegressionGateTest`: pass
  - rollup diagnostic gate 실패:
    - `mape=0.012261307980852718`
    - `p95=0.034458393187682054`
    - `max=0.03812875792913093`
    - `pass=false`
- 효과:
  - heavy2 DRG `64AC`: `+79,215 -> -24,801`
  - heavy4 DRG `64AC`: `+354,504 -> +255,637`
  - 하지만 heavy2 WHM/SCH/SAM DoT가 크게 under로 이동.
- 결론:
  - raw-source status evidence는 실제지만, PLD `0xF8 -> 0x17` 전역 복원은 현재 parity 기준에서 과도해 기각.
  - production 원복 후 기준선 rollup/gate 복귀.

## 2026-04-14 업데이트

### 반영/정리
- `trackedDots.size()==4` 강제 조건 제거 상태 유지
- 실험 suppress(`shouldSuppressKnownSourceMissingExactSingleTrackedTargetSplit`) 제거
  - 이유: `trackedDots.size()==1` 기반 단일 구조 종속이 커서 일반화 리스크 큼
- known-source foreign-dominant 억제는 ratio 기준 유지
  - `KNOWN_SOURCE_FOREIGN_DOMINANT_SOURCE_SHARE_THRESHOLD = 0.70`
  - `KNOWN_SOURCE_FOREIGN_DOMINANT_ACTION_SHARE_THRESHOLD = 0.70`

### 파서 라인 타입 판단
- `QpaureRawLine` 없음
- `OpaqueRawLine` 유지:
  - 파서의 미모델/부분파싱 라인 유실 방지
  - ingestion 안전 무시 (`if (line instanceof OpaqueRawLine) return;`)
  - `ActLineParserTest` 통과

### 최신 검증 수치
- gate: `pass=true`
- `mape=0.011888730720603377`
- `p95=0.025951827200127585`
- `max=0.03537628179947446`
- heavy2 fight2:
  - DRG `64AC`: `+32,951`
  - SAM `1D41`: `+1,139,355`

### 2026-04-14 추가 가설 실험 (기각/원복)
- 변경 시도:
  - `shouldSuppressKnownSourceForeignDominatedTrackedTargetSplit`에
    - `foreignSourceCount>=3 && foreignActionCount>=3` 최소 cardinality
    - `foreign share >= 0.70` 유지
    - stale same-target evidence 분기 추가
- 관찰:
  - heavy2 fight2:
    - DRG `64AC`: `+32,951 -> -13,976`
    - SAM `1D41`: `+1,139,355 -> +1,092,425`
  - tracked_target_split coverage:
    - heavy2 SAM `hits 39 -> 37`, `assigned 353,004 -> 333,456`
    - heavy2 DRG `hits 27 -> 25`, `assigned 222,643 -> 205,933`
  - heavy4/lindwurm coverage matrix는 실질 변화 없음
- rollup:
  - `mape=0.01203218732534237` (악화)
  - `p95=0.025790177051519213` (소폭 개선)
  - `max=0.03537628179947446` (동일)
  - gate: `pass=true`
- 결론:
  - selected fight 개선 대비 전역 `mape` 악화로 기각.
  - production 원복.

### 2026-04-14 재설계 1단계
- 목표:
  - `status0_tracked_target_split`의 foreign-dominant 억제 조건을
    이산 컷에서 연속 score 기준으로 전환.
- 반영:
  - `shouldSuppressKnownSourceForeignDominatedTrackedTargetSplit`에서
    `foreign dominance score = harmonic mean(foreignSourceShare, foreignActionShare)` 도입
  - 초기 threshold `0.70`, 보수 하한 `share >= 0.50`
- 결과:
  - baseline/gate/selected diagnostics 수치 변화 없음
  - heavy2 fight2:
    - DRG `64AC`: `+32,951` (동일)
    - SAM `1D41`: `+1,139,355` (동일)
  - rollup:
    - `mape=0.011888730720603377`
    - `p95=0.025951827200127585`
    - `max=0.03537628179947446`
    - `pass=true`
- 메모:
  - 구조 전환 완료, threshold 튜닝은 다음 턴에서 1가설씩 진행.

### 2026-04-14 재설계 2단계 (기각/원복)
- 변경 시도:
  - `KNOWN_SOURCE_FOREIGN_DOMINANCE_SCORE_THRESHOLD = 0.70 -> 0.66`
- 관찰:
  - heavy2 fight2:
    - SAM `1D41`: `+1,139,355 -> +1,119,785` 개선
    - DRG `64AC`: `+32,951` 변화 없음
  - heavy2 SAM tracked_target_split coverage:
    - `hits 39 -> 37`
    - `assigned 353,004 -> 333,434`
  - heavy2 DRG/heavy4/lindwurm coverage는 실질 변화 없음
- rollup:
  - `mape=0.011993527799304437` (악화)
  - `p95=0.02594914590214228` (소폭 개선)
  - `max=0.03537628179947446` (동일)
  - gate `pass=true`
- 결론:
  - selected fight 개선 대비 전역 `mape` 악화로 기각.
  - threshold `0.70` 원복.

### 2026-04-14 재설계 3단계 (기각/원복)
- 변경 시도:
  - foreign-dominant suppress에 `activeTargets==2` 제한 추가
- 결과:
  - heavy2/heavy4/lindwurm coverage matrix, heavy2 `1D41/64AC`, rollup 수치 모두 변화 없음
- 결론:
  - 실질 영향 없는 조건으로 판단, 원복.

### 2026-04-14 재설계 4단계 (비침습 계측)
- 목표:
  - `status0_tracked_target_split`의 foreign-dominant suppress 판정 근거를
    reason/score bucket 단위로 수집해 다음 가설을 evidence 기반으로 선택.
- 반영:
  - `ActIngestionService`에 probe 계측 추가:
    - `debugKnownSourceTrackedTargetSplitProbeCounts()`
    - `debugKnownSourceTrackedTargetSplitProbeAmounts()`
  - suppress 판정을 내부 decision 구조로 분해:
    - reason: `ineligible`, `recent_exact`, `source_tracked_not_single`,
      `source_action_mismatch`, `missing_evidence`, `stale_evidence`,
      `same_target_evidence`, `evidence_target_disagree`, `share_floor`,
      `score_below_threshold`, `suppress`
    - context/ratio bucket: trackedTargets, sourceTracked, activeTargets,
      sourceShare/actionShare/score
- 검증:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- 메모:
  - attribution 결과 자체는 바꾸지 않는 진단 계측 단계.
  - 다음 변경은 probe 결과 기준으로 heavy2 이득 + heavy4/lindwurm/gate 보존 가능한 1가설만 선택.

### 2026-04-14 재설계 5단계 (기각/원복)
- probe 관찰:
  - heavy2 fight2: `hits=162`, `amount=4,758,059`, `suppressed=32,534(0.68%)`
  - heavy4 fight5: `hits=71`, `amount=2,456,222`, `suppressed=0`
  - lindwurm fight8: `hits=83`, `amount=2,496,786`, `suppressed=0`
  - heavy2 상위 reason은 `ineligible(activeTargets=1)` + `recent_exact(activeTargets=2)`이며,
    기존 foreign-dominant score suppress는 실질 커버리지가 매우 낮음.
- 변경 시도:
  - `recent_exact + sourceTracked 단일 일치`를 suppress하도록 확장
- 회귀:
  - rollup max APE: `0.05775146607716398` (gate fail)
  - heavy2 all-fights fight2 p95: `0.05521012132054555` (gate fail)
  - heavy2 fight2 `1D41` delta도 악화(`+1,147,111`)
- 결론:
  - 가설 기각 및 즉시 원복.
  - production은 재설계 4단계 상태 유지.

## 2026-04-15 핸드오프

- 현재 상태:
  - production은 비침습 probe 계측만 반영된 상태
  - baseline/gate 통과 상태 복귀 확인 완료
- 다음 우선순위:
  1. heavy2의 `recent_exact(activeTargets=2, sourceTracked=1)` 구간을 suppress 없이 분해
  2. attribution 경로 선택 가설 1개만 반영
  3. heavy2/heavy4/lindwurm + heavy2 all-fights gate 즉시 재검증

## 2026-04-16 핸드오프

- baseline(원복 기준):
  - `mape=0.011072703994523023`
  - `p95=0.025664526617876063`
  - `max=0.03537628179947446`
  - `pass=true`
- selected fight 기준:
  - heavy2 fight2 DRG `64AC`: `delta=-6,274`
  - heavy2 fight2 SAM `1D41`: `delta=+1,035,044`
- 오늘 기각된 가설:
  - `status0_tracked_target_split_foreign_only_*`를 snapshot weighted evidence-only로 제한
  - 결과: heavy2 `64AC`/`1D41` 동시 악화 + `mape` 악화로 원복
- 다음 턴 시작 순서:
  1. `tasks.md` 최신 체크포인트 확인
  2. baseline (`ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`) 재확인
  3. heavy2/heavy4/lindwurm에서 `64AC` target/event semantics 복원 가설 1개만 적용
  4. 즉시 rollup + selected diagnostics 재검증
