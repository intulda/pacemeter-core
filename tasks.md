# paceMeter 작업 메모

## 고정 목표
- 최우선: `live rDPS parity`
- 기준: `pacemeter live rDPS ~= FFLogs companion live rDPS`
- 제약:
  - selected fight 단일 튜닝 금지
  - heavy2 / heavy4 / lindwurm / heavy2 all-fights gate 동시 유지
  - explainable attribution만 허용

## 세션 규약
1. `tasks.md` + `docs/parity-patch-notes.md` 최신 checkpoint 확인
2. baseline 확인
   - `ActIngestionServiceTest`
   - `SubmissionParityRegressionGateTest`
3. heavy2/heavy4/lindwurm 진단
4. 가설 1개만 production 반영
5. gate/diagnostics 즉시 재검증

## 2026-04-10 체크포인트

### 환경
- `.gradle-home` 단일 사용으로 통일
- `.gradle-home` 외 Gradle 캐시 디렉터리(예: `.gradle-home-run`, `.gradle-home-ascii*`) 신규 생성 금지
- `.gradle-home-ascii*` 삭제 완료

### 현재 production 상수
- `KNOWN_SOURCE_STALE_OTHER_TARGET_WINDOW_MS = 4000`
- `KNOWN_SOURCE_TWO_TARGET_SAME_SOURCE_WEIGHT_FACTOR = 0.00`
- `FOREIGN_DOMINANT_ACTION_REDUCTION_FACTOR = 0.00`

### 최신 검증 결과
- 테스트:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- 롤업:
  - `mape=0.011888730720603377`
  - `p95=0.025951827200127585`
  - `max=0.03537628179947446`
  - `pass=true`

### heavy2 fight2 핵심 잔차
- DRG `64AC`: `+32,951`
- SAM `1D41`: `+1,139,355`

### 이번 턴 관찰
- `FOREIGN_DOMINANT_ACTION_REDUCTION_FACTOR` 추가 하향 스윕:
  - `0.30 -> 0.28 -> 0.26 -> 0.22 -> 0.14 -> 0.00`
- 스윕 내 관찰:
  - gate는 전 구간 `pass=true` 유지
  - `mape`는 단계적으로 개선
  - heavy2 `1D41` 잔차는 지속 개선
  - heavy2 `64AC`는 소폭 역행
  - 합산(`|1D41|+|64AC|`)은 개선
  - `p95`는 소폭 악화(여전히 gate 내)
- 결론:
  - 탐색 구간에서 역전점 미발견
  - 현재 우선 고정값은 `0.00`
- `KNOWN_SOURCE_TWO_TARGET_SAME_SOURCE_WEIGHT_FACTOR` 재스윕:
  - `0.13 -> 0.12 -> ... -> 0.01 -> 0.00`
  - 관찰: gate 유지, `mape/p95` 단조 개선, heavy2 `1D41/64AC` 동시 개선
  - 결론: 탐색 구간 내 역전점 미발견, 현재 고정값 `0.00`
- `status0_tracked_target_split` suppression 조건 1개 추가:
  - 조건: `acceptedBySource && status=0 && multi-target && recentExact=null && trackedDots.size()==4 && sourceTrackedDots(1) + recentSourceAction 일치 + recent action/status evidence가 같은 다른 target`일 때, 그리고 `foreignSourceCount>=3 && foreignActionCount>=3`일 때 tracked_target_split 억제
  - 관찰:
    - heavy2 `1D41` / `64AC` 동시 개선
    - gate 유지
    - `p95` 개선
    - `mape` 소폭 악화
  - 상태: 유지(조건부)
- 추가 재검증(같은 날):
  - 시도: suppression 범위를 `trackedDots.size()==3 && activeTargets==2`까지 확장
  - 결과: `mape=0.011993527799304437`로 악화, heavy2 `1D41`만 `+1,119,785`까지 개선
  - 해석: selected fight 개선 대비 전역 품질 손실이 더 큼
  - 조치: 확장안 기각/원복, `trackedDots.size()==4 && foreign>=3` 유지

## 다음 턴 우선순위
1. 고정값 유지: `same-source=0.00`, `foreign-dominant=0.00`
2. heavy2 `1D41`의 남은 tracked_target_split 누수(특히 dual-target lifecycle) 추가 분해
3. 전역 mape를 악화시키지 않는 evidence gate 후보 1개만 설계
4. baseline + gate + selected diagnostics 재검증

## 2026-04-12 체크포인트

### 오늘 실험 요약
- baseline 재확인:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- 시도 1: `trackedDots.size()==4` 완화(`>=3`, `>=4` 등)
  - 결과: heavy2 selected 일부 개선처럼 보이나 rollup `mape` 악화
  - 조치: 기각/원복
- 시도 2: `foreign-dominated` 억제 시 drop 대신 `status0_tracked_source_target_split` 재할당
  - 결과: heavy2 `64AC` 역행(`+32,951 -> +65,485`), `mape/p95` 악화
  - 조치: 기각/원복

### 현재 유지 상태
- production 규칙은 이전 안정 상태 유지
- heavy2 fight2 핵심 잔차:
  - DRG `64AC`: `+32,951`
  - SAM `1D41`: `+1,139,355`

### 내일 시작 TODO
1. `trackedCount` 절대값 조건 제거 방향으로 재설계
2. `foreign dominance + evidence conflict` 비율 기반 gate 가설 1개만 적용
3. 즉시 `ActIngestionServiceTest` + `SubmissionParityRegressionGateTest` + heavy2/heavy4/lindwurm diagnostics 재검증

## 2026-04-14 체크포인트

### 오늘 결정
- `trackedDots.size()==4` 강제 조건은 production에서 제거 유지
- 직전 실험으로 들어갔던 `shouldSuppressKnownSourceMissingExactSingleTrackedTargetSplit` 제거
  - 사유: `trackedDots.size()==1` 기반 단일 상황 suppress는 과도한 구조 종속 리스크가 큼
- `status0_tracked_target_split` 억제는 ratio/evidence 기반만 유지
  - `KNOWN_SOURCE_FOREIGN_DOMINANT_SOURCE_SHARE_THRESHOLD = 0.70`
  - `KNOWN_SOURCE_FOREIGN_DOMINANT_ACTION_SHARE_THRESHOLD = 0.70`

### OpaqueRawLine 판단
- `QpaureRawLine`는 코드베이스에 없음
- `OpaqueRawLine`는 유지
  - 파서에서 미모델/부분파싱 라인을 `null`로 유실하지 않고 보존
  - ingestion에서는 `instanceof OpaqueRawLine` 즉시 return으로 안전 무시
  - 파서 회귀 테스트(`ActLineParserTest`) 통과 확인

### 재검증 결과
- 테스트:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - `ActLineParserTest` pass
- rollup:
  - `mape=0.011888730720603377`
  - `p95=0.025951827200127585`
  - `max=0.03537628179947446`
  - `pass=true`
- heavy2 fight2 핵심 잔차:
  - DRG `64AC`: `+32,951`
  - SAM `1D41`: `+1,139,355`

### 다음 턴 시작 TODO
1. `status0_tracked_target_split`의 남은 이산 컷(`sourceTrackedDots.size()!=1` 등)을 score/ratio 연속 기준으로 치환 가능한지 진단
2. heavy2/heavy4/lindwurm + heavy2 all-fights gate 동시 확인
3. 가설 1개만 반영 후 즉시 baseline/gate 재검증

## 2026-04-14 추가 실험 (원복)

### 가설
- `status0_tracked_target_split` 억제에
  - `foreignSourceCount>=3 && foreignActionCount>=3`
  - `foreign share >= 0.70`
  - `recentExact=null` + `sourceTrackedDots=1/recentSourceAction 일치`
  - stale same-target evidence 조건
  을 추가하면 heavy2 `1D41/64AC`를 동시 감소시킬 수 있다고 가정.

### 관찰
- heavy2 fight2 target parity:
  - DRG `64AC`: `+32,951 -> -13,976`
  - SAM `1D41`: `+1,139,355 -> +1,092,425`
- tracked_target_split coverage 감소:
  - heavy2 SAM: `hits 39 -> 37`, `assigned 353,004 -> 333,456`
  - heavy2 DRG: `hits 27 -> 25`, `assigned 222,643 -> 205,933`
- heavy4/lindwurm tracked_target_split coverage는 실질 동일.

### 회귀
- gate: `pass=true` 유지
- rollup:
  - `mape=0.01203218732534237` (기준선 `0.011888730720603377` 대비 악화)
  - `p95=0.025790177051519213` (소폭 개선)
  - `max=0.03537628179947446` (동일)

### 결론
- selected fight 개선 대비 전역 `mape` 악화로 기각.
- production 코드 원복 완료.

## 2026-04-14 재설계 1단계 (진행중)

### 가설
- `foreignSourceShare>=0.70 && foreignActionShare>=0.70` 이산 컷을
  연속 score(`harmonic mean`)로 바꾸면 이후 튜닝 공간을 확보할 수 있다.

### 변경
- `shouldSuppressKnownSourceForeignDominatedTrackedTargetSplit`:
  - foreign dominance 판단을 `computeKnownSourceForeignDominanceScore()`로 치환
  - 보수적 하한(`source/action share >= 0.50`) 유지
  - threshold는 `0.70`으로 시작

### 검증
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass
- heavy2 fight2:
  - DRG `64AC`: `+32,951` (동일)
  - SAM `1D41`: `+1,139,355` (동일)
- coverage matrix:
  - heavy2/heavy4/lindwurm `status0_tracked_target_split` 동일
- rollup:
  - `mape=0.011888730720603377`
  - `p95=0.025951827200127585`
  - `max=0.03537628179947446`
  - `pass=true`

### 해석
- 구조는 연속 스코어 기반으로 전환됐지만, 현재 threshold에서는 동작이 기준선과 동일.
- 다음 턴은 threshold/score 가중치 1개만 조정해 heavy2 이득과 gate/rollup 보존을 탐색.

## 2026-04-14 재설계 2단계 (기각/원복)

### 가설
- foreign dominance score threshold를 `0.70 -> 0.66`으로 낮추면
  heavy2의 `status0_tracked_target_split` 일부를 추가 억제해 `1D41`를 줄일 수 있다.

### 관찰
- heavy2 fight2:
  - SAM `1D41`: `+1,139,355 -> +1,119,785` (개선)
  - DRG `64AC`: `+32,951` (변화 없음)
- coverage:
  - heavy2 SAM tracked_target_split `hits 39 -> 37`, `assigned 353,004 -> 333,434`
  - heavy2 DRG/heavy4/lindwurm는 실질 변화 없음

### 회귀
- gate: `pass=true` 유지
- rollup:
  - `mape=0.011993527799304437` (악화)
  - `p95=0.02594914590214228` (소폭 개선)
  - `max=0.03537628179947446` (동일)

### 결론
- selected fight 단일 개선 대비 전역 `mape` 악화로 기각.
- threshold `0.70` 원복, baseline/gate/rollup 기준선 복귀 확인 완료.

## 2026-04-14 재설계 3단계 (기각/원복)

### 가설
- foreign-dominant suppress를 `activeTargets==2`(dual-target lifecycle)로 제한하면
  heavy2 특이 케이스만 타격하고 전역 부작용을 줄일 수 있다.

### 결과
- heavy2/heavy4/lindwurm coverage matrix, heavy2 핵심 잔차, rollup 모두 변화 없음.
- 의미 있는 동작 변화가 없어 기각/원복.

## 2026-04-14 재설계 4단계 (진단 계측 추가)

### 목적
- `status0_tracked_target_split`의 foreign-dominant suppress가
  어떤 근거(reason)에서 걸리는지/안 걸리는지 정량 근거를 먼저 확보.
- production attribution 동작은 변경하지 않고 evidence 분해만 강화.

### 변경
- `ActIngestionService`에 known-source tracked-target-split probe 계측 추가:
  - `debugKnownSourceTrackedTargetSplitProbeCounts()`
  - `debugKnownSourceTrackedTargetSplitProbeAmounts()`
- foreign-dominant suppress 판단을 내부 decision 구조로 분해:
  - reason(`ineligible`, `recent_exact`, `source_action_mismatch`, `share_floor`, `score_below_threshold`, `suppress` 등)
  - context bucket(`trackedTargets`, `sourceTracked`, `activeTargets`)
  - ratio/score bucket(`sourceShare`, `actionShare`, `score`)
- 계측은 hit count/assigned amount만 누적하며,
  기존 suppress 결과(true/false)와 분기 순서는 동일 유지.

### 검증
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass

### 해석
- 이번 단계는 보정 규칙 추가가 아니라 원인 분해용 텔레메트리 확보 단계.
- 다음 턴에서 heavy2/heavy4/lindwurm 동일 지점에 대해
  suppress reason 분포를 비교해 explainable 가설 1개만 선정 가능.

## 2026-04-14 재설계 5단계 (기각/원복)

### 관찰 (probe)
- selected fights probe 요약:
  - heavy2 fight2: `hits=162`, `amount=4,758,059`, `suppressed=32,534(0.68%)`
  - heavy4 fight5: `hits=71`, `amount=2,456,222`, `suppressed=0`
  - lindwurm fight8: `hits=83`, `amount=2,496,786`, `suppressed=0`
- heavy2 상위 reason은
  - `ineligible(activeTargets=1/sourceTracked=0)` 대량
  - `recent_exact(activeTargets=2/sourceTracked=1)` 대량
  - 기존 `score>=0.70 suppress`는 실질 1 hit만 발생

### 가설
- `recent_exact + sourceTracked 단일 일치`를 suppress하면 heavy2 오염을 줄일 수 있다고 가정.

### 결과
- gate 실패:
  - rollup max APE: `0.05775146607716398` (기준 0.05 초과)
  - heavy2 all-fights fight2 p95: `0.05521012132054555` (기준 0.055 초과)
- heavy2 fight2 `1D41` target parity도 악화:
  - `delta=+1,147,111` (기준선 `+1,139,355` 대비 악화)

### 조치
- 가설 즉시 원복 완료.
- production 동작은 재설계 4단계(비침습 계측) 상태로 복귀.

## 2026-04-15 이어하기 메모

### 현재 안전 상태
- production 규칙 변경 없음(재설계 4단계 계측만 유지)
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass

### 확정 관찰
- heavy2 fight2 `status0_tracked_target_split` probe:
  - `hits=162`, `amount=4,758,059`
  - suppress 실효: `32,534(0.68%)`
- heavy4/lindwurm는 suppress 실효 `0`
- 단순 suppress 확장은 gate 깨짐(재설계 5단계에서 기각/원복 완료)

### 다음 시작점 (1가설 원칙)
1. `recent_exact(activeTargets=2, sourceTracked=1)` 구간만 분리 진단
2. suppress가 아니라 attribution 경로 선택(분배 방식) 후보 1개만 실험
3. 즉시 `ActIngestionServiceTest` + `SubmissionParityRegressionGateTest` + heavy2 target/mode 재검증
