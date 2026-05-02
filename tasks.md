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

## 2026-04-15 실험 추가 (기각/원복)

### baseline 복귀 재확인
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass
- rollup:
  - `mape=0.011072703994523023`
  - `p95=0.025664526617876063`
  - `max=0.03537628179947446`
  - `pass=true`

### heavy2 fight2 현재 기준
- SAM `1D41`: `delta=+1,035,044`
- DRG `64AC`: `delta=-6,274`

### 기각 1: fallback weighted 우선 분배
- 변경:
  - `status0_fallback_tracked_target_split` 앞에
    `status0_fallback_weighted_tracked_target_split` 분기 추가
    (unknown status + multi-target에서 snapshot weight 사용)
- 결과:
  - SAM `1D41` 악화: `+1,151,974`
  - DRG `64AC` 악화: `-54,679`
  - actor totals 역행
- 조치: 원복

### 기각 2: recentExact 기반 exact-only split
- 변경:
  - `status0_exact_same_source_tracked_target_split` 분기 추가
    (accepted known-source + multi-target + recentExact 일치 시 exact action만 분배)
- 결과:
  - rollup 악화:
    - `mape=0.011842769505729739`
    - `p95=0.02780317682261673`
  - heavy2 악화:
    - SAM `1D41`: `+1,057,823`
    - DRG `64AC`: `+104,817`
- 조치: 원복

### 다음 우선순위
1. `heavy2.fight2.sam`의 fallback 오염 구간(`accepted=false`, `sourceTracked=0`, `recentExact/recentSource=null`)에 한정해 evidence 분해
2. suppress 확대가 아닌, source-evidence 부재 fallback의 explainable 분기 1개만 실험
3. 즉시 baseline/gate/selected 재검증

## 2026-04-15 추가 실험 2 (기각/원복)

### 가설
- unknown status fallback에서
  `activeTargets>1` + `recentExact/recentSource 모두 없음` + `sourceTracked=0` 케이스를 suppress하면
  heavy2 `1D41` 오염을 줄일 수 있다고 가정.

### 결과
- gate 실패:
  - `rollup pass=false`
  - `mape=0.01269655421707582`
  - `p95=0.031178025400181254`
- heavy2 fight2:
  - SAM `1D41`: `+799,243` (표면상 개선)
  - DRG `64AC`: `-131,494` (과보정 악화)
- heavy2 actor totals는 SAM/DRG/WHM/SCH 동시 역행.

### 조치
- 규칙 즉시 원복.
- 원복 후 기준선 복귀 확인:
  - `rollup mape=0.011072703994523023`
  - `p95=0.025664526617876063`
  - `max=0.03537628179947446`
  - heavy2 `1D41=+1,035,044`, `64AC=-6,274`

## 2026-04-15 추가 실험 3 (기각/원복)

### 가설
- fallback `status0_tracked_target_split`에서
  `accepted=false/sourceTracked=0/recent evidence 없음/activeTargets>1` 구간만
  equal split 대신 `expiresAt` 기반 가중 분배를 적용하면 stale 오염을 줄일 수 있다고 가정.

### 결과
- gate는 통과했지만 전역/selected 동시 악화:
  - `mape=0.011686906041190595` (악화)
  - SAM `1D41`: `+1,185,243` (악화)
  - DRG `64AC`: `-52,018` (악화)
- 조치: 즉시 원복

### 원복 후 복귀 확인
- `rollup mape=0.011072703994523023`
- `p95=0.025664526617876063`
- heavy2 `1D41=+1,035,044`, `64AC=-6,274`

## 2026-04-15 추가 실험 4 (기각/원복)

### 가설
- fallback `status0_tracked_target_split`에서
  source job이 unknown-status DoT whitelist 밖이고,
  `recentExact/recentSource` 근거가 없으며 sourceTracked도 없는 multi-target 케이스만 suppress.

### 결과
- gate는 통과했지만 selected 동시 악화:
  - `mape=0.011221629276891581` (악화)
  - `p95=0.02555281693406692` (소폭 개선)
  - SAM `1D41`: `+993,868` (개선)
  - DRG `64AC`: `-47,447` (악화)
- heavy2 actor totals에서도 DRG/WHM/SCH가 역행.

### 조치
- 규칙 즉시 원복.
- 원복 후 기준선 복귀 확인:
  - `rollup mape=0.011072703994523023`
  - `p95=0.025664526617876063`
  - heavy2 `1D41=+1,035,044`, `64AC=-6,274`

## 2026-04-15 추가 실험 5 (무효/원복)

### 가설
- `resolveRecentSourceUnknownStatusActionId`에서 by-source map 미히트 시
  target-scoped source evidence(`unknownStatusDotAction/StatusEvidenceBySource`)를 보조로 사용하면
  fallback 진입 전 evidence 복원률이 늘어날 수 있다고 가정.

### 결과
- rollup/selected 지표가 baseline과 완전히 동일:
  - `mape=0.011072703994523023`
  - `p95=0.025664526617876063`
  - SAM `1D41=+1,035,044`
  - DRG `64AC=-6,274`

### 조치
- 동작 변화가 없어 코드 원복(복잡도 증가 방지).

## 2026-04-16 추가 실험 6 (기각/원복)

### 가설
- `status0_fallback_tracked_target_split`에서
  `accepted=false + sourceTracked=0 + recentExact/recentSource=null + activeTargets>1 + source=partyMember`
  인 근거 부재 known-source fallback만 suppress하면 heavy2 오염을 줄일 수 있다고 가정.

### 결과
- heavy2 selected는 개선:
  - SAM `1D41`: `+1,035,044 -> +706,222`
  - DRG `64AC`: `-6,274 -> -164,710` (과보정)
- 전역 품질 크게 악화, gate 실패:
  - `mape=0.013439955422916669`
  - `p95=0.03488555031539117`
  - `max=0.03750632472618677`
  - `pass=false`

### 조치
- 규칙 즉시 원복.
- 원복 후 baseline 재확인:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` + selected diagnostics pass
  - rollup `mape=0.011072703994523023`, `p95=0.025664526617876063`, `max=0.03537628179947446`, `pass=true`

## 2026-04-16 타임라인 대조 체크포인트 (진단 우선순위 재정렬)

### 관찰 1: heavy2 fight2 1D41/64AC는 "시간축 누수"보다 "이벤트 의미/타깃 의미" 차이가 큼
- SAM `1D41` direct vs dot:
  - `emittedTotal=1,588,932`, `raw21Total=276,944`, `inferredDotTotal=1,311,988`
  - `fflogsAbilityTotal=1,542,816`, `delta=+46,116` (ability total 기준으로는 근접)
- DRG `64AC` direct vs dot:
  - `emittedTotal=1,927,842`, `raw21Total=967,058`, `inferredDotTotal=960,784`
  - `fflogsAbilityTotal=1,785,989`, `delta=+141,853`

### 관찰 2: heavy2 fight2 DRG는 FFLogs window 바깥 누수 0
- `hitLeakVsFflogsWindows`:
  - `outsideWindowHits=0`
  - `outsideWindowDamage=0`
- 즉, 과집계의 주원인은 "window 밖 이벤트"가 아님.

### 관찰 3: status=0 분배가 타깃별 로컬 합을 크게 밀어올림
- heavy2 fight2 SAM target mix:
  - 레드 핫: `status0Assigned=686,889 / localEmitted=798,260`
  - 딥 블루: `status0Assigned=496,752 / localEmitted=648,223`
  - 수중 감옥: `status0Assigned=128,347 / localEmitted=142,449`
- heavy2 fight2 DRG target mix:
  - 레드 핫: `status0Assigned=692,721 / localEmitted=1,340,269`
  - 딥 블루: `status0Assigned=190,050 / localEmitted=415,659`
  - 수중 감옥: `status0Assigned=78,013 / localEmitted=171,914`

### 결론 (우선순위)
1. `시간창 suppress` 계열은 우선순위 하향
2. 다음은 `target semantics / event semantics` 분리 진단을 먼저 고정
3. production 가설은 `status=0 attribution 근거 복원`만 1개씩 유지

## 2026-04-16 타임라인 대조 추가 (confidence 축 고정)

### baseline 재확인
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass
- rollup:
  - `mape=0.011072703994523023`
  - `p95=0.025664526617876063`
  - `max=0.03537628179947446`
  - `pass=true`

### heavy2 fight2 target timeline (local vs fflogs)
- SAM `1D41`:
  - local targets:
    - 레드 핫 `798,260` (68 hits)
    - 딥 블루 `648,223` (48 hits)
    - 수중 감옥 `142,449` (16 hits)
  - fflogs targets:
    - `B` `222,742` (10 hits)
    - `11` `302,942` (10 hits)
    - `18` `28,204` (2 hits)
- DRG `64AC`:
  - local targets:
    - 레드 핫 `1,340,269` (82 hits)
    - 딥 블루 `415,659` (23 hits)
    - 수중 감옥 `171,914` (12 hits)
  - fflogs targets:
    - `B` `1,295,096` (32 hits)
    - `11` `451,218` (10 hits)
    - `18` `187,802` (4 hits)

### fflogsAbilityVsEvents confidence (동일 지표로 교차비교)
- heavy2 fight2 SAM `1D41`:
  - `abilityTotal=1,542,816`
  - `eventTotal=553,888`
  - `agreement=0.3590`, `ratio=0.3590`, `confidence=low`
- heavy2 fight2 DRG `64AC`:
  - `abilityTotal=1,785,989`
  - `eventTotal=1,934,116`
  - `agreement=0.9234`, `ratio=1.0829`, `confidence=medium`
- heavy4 fight5 DRG `64AC`:
  - `abilityTotal=1,823,449`
  - `eventTotal=1,738,722`
  - `agreement=0.9535`, `ratio=0.9535`, `confidence=medium`
- lindwurm fight8 DRG `64AC`:
  - `abilityTotal=1,434,142`
  - `eventTotal=1,454,918`
  - `agreement=0.9857`, `ratio=1.0145`, `confidence=high`

### 우선순위 결론
1. `1D41`은 low-confidence 구간이 커서, 즉시 production 분배 튜닝 대상이 아님
2. `64AC`는 heavy2/heavy4/lind 비교가 가능하므로, shared GUID/target semantics 분해를 먼저 진행
3. 다음 production 변경은 `status=0` 분배식이 아니라, evidence 복원(특히 target/event semantics 정합) 1가설만 적용

## 2026-04-16 추가 실험 7 (기각/원복)

### 가설
- `status0_tracked_target_split_foreign_only_*`의 무근거 equal split이 heavy2 `64AC` target semantics를 흔든다고 보고,
  foreign-only 경로를 `snapshot weighted evidence`가 있을 때만 허용.
  (weighted evidence가 없으면 일반 `status0_tracked_target_split`로 폴백)

### 관찰
- heavy2 fight2 DRG `64AC`:
  - `delta=-6,274 -> -29,211` (절대오차 악화)
- heavy2 fight2 SAM `1D41`:
  - `delta=+1,035,044 -> +1,060,714` (악화)
- rollup:
  - `mape=0.01109501889418931` (악화)
  - `p95=0.02555281693406692` (개선)
  - gate는 `pass=true` 유지

### 해석
- heavy2 `64AC`를 개선하지 못했고 `1D41`까지 동반 악화.
- 전역 mape도 악화되어 채택 근거 부족.

### 조치
- 변경 즉시 원복.
- 원복 후 baseline 복귀 확인:
  - rollup: `mape=0.011072703994523023`, `p95=0.025664526617876063`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - DRG `64AC=-6,274`
    - SAM `1D41=+1,035,044`

## 2026-04-16 이어가기 진단 재확인 (production 변경 없음)

### baseline 재확인
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass

### selected fight 재확인
- heavy2 fight2 SAM `1D41` target timeline:
  - local: 레드 핫 `798,260`, 딥 블루 `648,223`, 수중 감옥 `142,449`
  - fflogs event: `B=222,742`, `11=302,942`, `18=28,204`
- heavy2 fight2 DRG `64AC` target timeline:
  - local: 레드 핫 `1,340,269`, 딥 블루 `415,659`, 수중 감옥 `171,914`
  - fflogs event: `B=1,295,096`, `11=451,218`, `18=187,802`

### fflogs ability vs events confidence
- heavy2 fight2 SAM `1D41`:
  - `abilityTotal=1,542,816`, `eventTotal=553,888`, `agreement=0.3590`, `ratio=0.3590`, `confidence=low`
- heavy2 fight2 DRG `64AC`:
  - `abilityTotal=1,785,989`, `eventTotal=1,934,116`, `agreement=0.9234`, `ratio=1.0829`, `confidence=medium`
- heavy4 fight5 DRG `64AC`:
  - `abilityTotal=1,823,449`, `eventTotal=1,738,722`, `agreement=0.9535`, `ratio=0.9535`, `confidence=medium`
- lindwurm fight8 DRG `64AC`:
  - `abilityTotal=1,434,142`, `eventTotal=1,454,918`, `agreement=0.9857`, `ratio=1.0145`, `confidence=high`

### known-source tracked_target_split probe 재확인
- heavy2 fight2:
  - `hits=162`, `amount=4,758,059`
  - `suppressHits=1(0.62%)`, `suppressAmount=32,534(0.68%)`
  - top reasons(금액):
    - `ineligible(activeTargets=1/sourceTracked=0)` 우세
    - `recent_exact(activeTargets=2/sourceTracked=1)` 우세
- heavy4 fight5:
  - `hits=71`, `amount=2,456,222`, `suppress=0`
- lindwurm fight8:
  - `hits=83`, `amount=2,496,786`, `suppress=0`

### heavy2 mode breakdown 재확인
- SAM `1D41` 상위 mode:
  - `status0_tracked_target_split(target=딥 블루) amount=240,775`
  - `status0_fallback_tracked_target_split(target=레드 핫) amount=231,105`
  - `status0_snapshot_redistribution(target=레드 핫) amount=147,432`
- DRG `64AC` 상위 mode:
  - `status0_tracked_target_split(target=레드 핫) amount=189,725`
  - `status0_fallback_tracked_target_split(target=레드 핫) amount=158,436`
  - `status0_accepted_by_source(target=레드 핫) amount=145,753`

### 결론
- 현재 병목 축은 이전과 동일:
  - `1D41`: low-confidence(`ability vs events`) + status0 split 다중 경로
  - `64AC`: high/medium confidence 구간 비교 가능, 다만 status0 split 기여가 여전히 큼
- 다음 턴은 suppress 확장이 아니라,
  `ineligible(activeTargets=1/sourceTracked=0) -> activeTargets=2` 전이 구간에서
  action/target evidence 복원 가설 1개만 적용 후 gate 재검증.

## 2026-04-16 activeTargets 조건 수정 (진행)

### 배경
- `activeTargets == 2` 고정은 보스+잡몹(adds)처럼 `3+` active target 구간을 구조적으로 배제.
- dual-target 특화로 시작한 조건이 일반화 측면에서 과도하게 좁았음.

### 변경
- 파일: `ActIngestionService.java`
- 아래 2개 분기의 active target 조건을 `==2`에서 `>=2` 의미로 완화:
  - `resolveKnownSourceTwoTargetWeightedTrackedTargetSplit`
  - `resolveKnownSourceDualTargetForeignOnlyTrackedSplit`
- 구현은 guard를 `countTrackedTargetsWithActiveDots() < 2 -> return`으로 변경.

### 검증
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass

### 메모
- 이번 변경은 evidence 분기의 적용 대상을 `dual-target only`에서 `multi-target(2+)`로 확장.
- 다음 턴에 selected diagnostics(heavy2/heavy4/lindwurm, mode/target/probe)를 재확인해
  `3+` 구간에서의 실제 동작 변화와 부작용 여부를 추가 확인.

## 2026-04-16 activeTargets 2+ 확장 재검증 (채택 유지)

### 변경(유지)
- `ActIngestionService.java`
  - `resolveKnownSourceTwoTargetWeightedTrackedTargetSplit`: `activeTargets != 2` 제거, `activeTargets < 2`만 배제
  - `resolveKnownSourceDualTargetForeignOnlyTrackedSplit`: `activeTargets != 2` 제거, `activeTargets < 2`만 배제

### baseline / gate
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass
- rollup:
  - `mape=0.010996830747457277` (기준선 `0.011072703994523023` 대비 개선)
  - `p95=0.025614369256908187` (기준선 `0.025664526617876063` 대비 개선)
  - `max=0.03537628179947446` (동일)
  - `pass=true`

### selected fight 관찰 (heavy2 fight2)
- SAM `1D41` target timeline:
  - local: 레드 핫 `798,260`, 딥 블루 `648,223`, 수중 감옥 `135,325`
  - fflogs event total: `553,888`
  - delta: `+1,027,920` (기준선 `+1,035,044` 대비 개선 `-7,124`)
- DRG `64AC` target timeline:
  - local: 레드 핫 `1,340,269`, 딥 블루 `415,659`, 수중 감옥 `164,790`
  - fflogs event total: `1,934,116`
  - delta: `-13,398` (기준선 `-6,274` 대비 역행 `-7,124`)
- 해석:
  - `1D41` 개선분과 `64AC` 역행분이 동일 크기로 상쇄되어
    `|1D41| + |64AC|` 합산 절대오차는 실질 동일.

### mode/probe
- heavy2 `knownSourceTrackedTargetSplitProbe` totals:
  - `hits=162`, `amount=4,758,059`, suppress `0.68%` (기준선과 동일)
- heavy4/lindwurm probe totals도 기준선과 동일(suppress 0)
- heavy2 mode breakdown 변화는 `수중 감옥` 축의 일부 amount 이동(약 `7,124`) 외 구조적 분포는 유사.

### 결론
- 전역 gate/rollup 악화 없이 유지되고, adds 포함 multi-target(2+) 구간을 구조적으로 배제하지 않게 됨.
- selected 합산절대오차는 동률이라 수치 이득은 제한적이지만, 모델 제약 관점에서 변경 유지.
- 다음 1가설은 suppression 확장이 아니라
  `activeTargets>=2 + sourceTracked=1 + recentSourceAction 일치`에서
  `target semantics` 근거 복원(특히 수중 감옥 축) 1개만 적용해 heavy2 `64AC` 역행분 회수 시도.

## 2026-04-16 추가 실험 8 (기각/원복)

### 가설
- `status0_tracked_target_split` 진입에서
  `known-source + activeTargets>=2 + sourceTracked=1 + recentSourceAction 일치`일 때
  `recentSourceAction`과 actionId가 같은 tracked 후보를 우선 분배하면
  heavy2 target semantics를 개선할 수 있다고 가정.

### 변경 시도
- 신규 분기 `status0_recent_source_action_tracked_target_split` 추가:
  - 기존 weighted split 다음, foreign-only split 이전에 적용
  - 일치 후보가 있으면 해당 후보들로 분배 후 return

### 결과
- gate 실패:
  - `mape=0.013249754126077247`
  - `p95=0.03167808136812332` (`0.03` 초과)
  - `max=0.03537628179947446`
  - `pass=false`
- selected도 동반 악화:
  - heavy2 SAM `1D41` local total `1,773,954` (과대 확대)
  - heavy2 DRG `64AC` local total `2,123,692` (과대 확대)

### 조치
- 변경 즉시 원복.
- 원복 후 재확인:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - rollup 복귀:
    - `mape=0.010996830747457277`
    - `p95=0.025614369256908187`
    - `max=0.03537628179947446`
    - `pass=true`

## 2026-04-16 내일 이어하기 핸드오프

### 현재 안전 상태(내일 시작 기준)
- production 유지 변경:
  - `activeTargets == 2` 고정 제거 (`<2`만 배제) 2개 분기
- baseline/gate:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`

### 오늘 확정된 기각 포인트
- `status0_recent_source_action_tracked_target_split` (recentSourceAction 우선 강제 분배):
  - gate fail (`p95=0.03167808136812332`)
  - heavy2 `1D41/64AC` 동반 과집계
  - 즉시 원복 완료

### 문제 구조 요약
- heavy2 `1D41`는 `ability vs events` confidence가 낮아 분배 튜닝 시 상쇄 이동 리스크 큼
- heavy2 `64AC`는 target semantics(레드 핫/딥 블루/수중 감옥) 축 이동이 핵심
- suppress/우선분배 계열은 “근거 복원”보다 “질량 이동”으로 귀결되는 경향

### 내일 첫 절차(고정)
1. `tasks.md` / `docs/parity-patch-notes.md` 확인
2. baseline 재확인:
   - `./gradlew.bat test --tests com.bohouse.pacemeter.application.ActIngestionServiceTest --no-daemon`
   - `./gradlew.bat test --tests com.bohouse.pacemeter.application.SubmissionParityRegressionGateTest --no-daemon`
3. selected diagnostics 재확인(heavy2/heavy4/lindwurm)
4. production 변경은 1가설만 허용

### 내일 권장 1가설 방향
- suppress/가중치 조정 금지.
- `activeTargets` 전이(특히 adds 등장/퇴장) 구간에서
  `source-action-target evidence` 누락을 복원/계측하는 비침습 1가설 우선.
- 성공 기준:
  - gate 유지
  - heavy2 `64AC`의 수중 감옥 축 역행 완화
  - heavy4/lindwurm 악화 없음

## 2026-04-17 추가 실험 9 (기각/원복)

### 선행 심층 관찰
- baseline/gate 재확인:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected diagnostics:
  - heavy2 fight2:
    - SAM `1D41` `delta=+1,027,920`
    - DRG `64AC` `delta=-13,398`
  - heavy2 known-source tracked_target_split probe:
    - `hits=162`, `amount=4,758,059`
    - `suppressHits=1(0.62%)`, `suppressAmount=32,534(0.68%)`
  - heavy4/lindwurm probe suppress는 `0%`
- 해석:
  - suppress 축의 실효량이 매우 작아 대형 보정 레버리지 아님.
  - `1D41`는 `fflogsAbilityVsEvents confidence=low(0.3590)`로 직접 튜닝 리스크 큼.

### 가설
- `status0_tracked_target_split_foreign_only_single_target`의 equal split 대신
  snapshot weighted split을 우선 적용하면 target semantics가 개선될 수 있다고 가정.

### 결과
- gate 자체는 유지(pass=true)였으나 selected 악화:
  - heavy2 fight2 SAM `1D41`: `+1,027,920 -> +1,049,499` (악화, `+21,579`)
  - heavy2 fight2 DRG `64AC`: `-13,398 -> -44,816` (악화, `-31,418`)
- mode 관찰:
  - 신규 mode `status0_weighted_tracked_target_split_foreign_only_single_target`가 실제 발동
  - `1D41/64AC` 질량 이동이 의도와 반대로 발생
- rollup:
  - `mape=0.010943409925028494`
  - `p95=0.02555281693406692`
  - `max=0.03537628179947446`
  - `pass=true`

### 조치
- selected 핵심 residual 동시 악화로 가설 기각, 즉시 원복.
- 원복 후 복귀 확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`

### 운영 메모
- Windows 경로에서 `.gradle-home` 캐시 JAR 접근 거부(`AccessDeniedException`)가 간헐 발생.
- 동일 검증은 `subst X:` + `JAVA_HOME=X:\\.jdk17` + `GRADLE_USER_HOME=X:\\.gradle-home`로 안정 재현.

## 2026-04-17 추가 실험 10 (기각/원복)

### 가설
- `status0_tracked_target_split` 진입에서
  `accepted known-source + status=0 + activeTargets>=2 + recentExact=null + sourceTracked=1 + recentSourceAction 일치`
  조건에 한해, 강제 분배가 아닌 약한 가중치(`action 일치 후보 +0.25`)를 적용하면
  heavy2 타깃 오염을 줄일 수 있다고 가정.

### 변경
- `ActIngestionService.java`
  - 신규 분기 `status0_soft_recent_action_tracked_target_split` 추가
  - 위치: `status0_weighted_tracked_target_split` 다음, foreign-only 분기 이전
  - 분배식: equal split 대신 candidate weight(`1.0` / recentSourceAction 매치 `1.25`) 기반 planner 사용

### 결과
- gate는 `pass=true`였지만 전역/selected 동시 악화:
  - rollup:
    - `mape=0.011558180644848426` (악화)
    - `p95=0.02596307427311645` (악화)
    - `max=0.03537628179947446` (동일)
  - heavy2 fight2:
    - SAM `1D41`: `+1,027,920 -> +1,079,477` (악화)
    - DRG `64AC`: `-13,398 -> +35,107` (악화)
- mode breakdown에서 신규 분기가 실제로 상당량 발동:
  - SAM `1D41`: `status0_soft_recent_action_tracked_target_split` 총 `156,422`
  - DRG `64AC`: `status0_soft_recent_action_tracked_target_split` 총 `87,539`

### 조치
- selected 핵심 residual과 rollup 동시 악화로 즉시 원복.
- 원복 후 재검증 복귀:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`

### 환경 메모
- 로컬 샌드박스에서 `.gradle-home` JAR 접근 거부가 반복되어, 검증 명령은 `require_escalated`로 재실행해 통과 확인.
- 임시 생성한 `.gradle-home-temp` 디렉터리는 삭제 완료.

## 2026-04-17 추가 실험 11 (무효/원복)

### 가설
- `activeTargets>1` 구간에서 `recentSourceAction` 조회를 by-source보다 `source+target scoped` evidence 우선으로 바꾸면
  전이 구간 누수를 줄일 수 있다고 가정.

### 변경
- `resolveRecentSourceUnknownStatusActionId`에서
  multi-target 시 `unknownStatusDotApplications/statusApplications`의 `(sourceId,targetId)` key를 우선 조회.

### 결과
- baseline/gate/selected/rollup 수치 변화 없음(완전 동일).
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`

### 조치
- 동작 변화가 없어 코드 원복.

## 2026-04-17 추가 실험 12 (무효/원복)

### 가설
- `known-source foreign-only split` 진입에서 `recentExact=null`이어도
  같은 target의 최신 status signal(source 일치)이 있으면 foreign-only를 막아
  전이 구간 분배를 개선할 수 있다고 가정.

### 변경
- `resolveKnownSourceDualTargetForeignOnlyTrackedSplit`
- `resolveKnownSourceSingleTargetForeignOnlyTrackedSplit`
  - `recentExact` 보조 근거로 `recentStatusSignalsByTarget` 조회 추가.

### 결과
- baseline/gate/selected/rollup 수치 변화 없음(완전 동일).
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`

### 조치
- 동작 변화가 없어 코드 원복.

### 다음 우선순위(갱신)
1. production 튜닝 재시도 전, `foreign-only 진입 reason` 자체를 별도 probe로 계측해 실제 커버리지(히트/금액)부터 확정
2. 커버리지가 의미 있는 bucket 1개에 대해서만 attribution 경로 변경 1가설 적용
3. 즉시 `ActIngestionServiceTest` + `SubmissionParityRegressionGateTest` + selected + rollup 재검증

## 2026-04-17 추가 실험 13 (진단 계측 추가, production 동작 동일)

### 목적
- `status0_tracked_target_split_foreign_only_*` 경로의 실제 진입/미진입 reason과 커버리지를
  selected fights 기준으로 정량화.

### 변경
- `ActIngestionService.java`
  - foreign-only probe 맵 추가:
    - `knownSourceForeignOnlySplitProbeCountByKey`
    - `knownSourceForeignOnlySplitProbeAmountByKey`
  - dual/single foreign-only 분기에서 reason 기반 계측 추가
    - reason 예: `ineligible`, `active_targets_lt2`, `active_targets_not1`,
      `tracked_or_source_cardinality`, `recent_exact_present`,
      `recent_source_mismatch`, `foreign_source_or_action_cardinality`, `include`
  - debug accessor 추가:
    - `debugKnownSourceForeignOnlySplitProbeCounts()`
    - `debugKnownSourceForeignOnlySplitProbeAmounts()`
- `SubmissionParityReportDiagnostics.java`
  - `debugKnownSourceForeignOnlySplitProbeBuckets_forSelectedFights_printsTopReasons` 추가
  - selected fights(heavy2/heavy4/lindwurm)에서 totals/top buckets 출력

### 관찰
- baseline/gate/selected/rollup 동일(동작 비침습):
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`
- foreign-only probe totals:
  - heavy2 fight2: `hits=177`, `amount=5,205,401`, `includeHits=22(12.43%)`, `includeAmount=759,669(14.59%)`
  - heavy4 fight5: `includeAmount=0`
  - lindwurm fight8: `includeAmount=0`

### 해석
- foreign-only include는 사실상 heavy2 특화 현상으로 확인됨(heavy4/lind 0).
- 다음 production 가설은 heavy2 include bucket을 줄이되, `1D41` 역행 여부를 함께 감시해야 함.

## 2026-04-17 추가 실험 14 (기각/원복)

### 가설
- heavy2 foreign-only include의 큰 축(`dual + trackedTargets=3 + activeTargets=2`)만 억제하면
  heavy2 `64AC`를 개선하고 다른 selected fight 영향은 제한될 수 있다고 가정.

### 변경
- `resolveKnownSourceDualTargetForeignOnlyTrackedSplit`에
  `trackedDots.size() < 4`이면 include 금지(`tracked_dual_lt4_for_include`) 추가.

### 결과
- heavy2 `64AC`는 개선됐으나(`-13,398 -> +2,260`), 핵심 지표 동시 악화:
  - heavy2 `1D41`: `+1,027,920 -> +1,075,254` (악화)
  - rollup: `mape=0.011346121465279808`, `p95=0.025733815692258116` (둘 다 악화)
- selected 합산 절대오차도 악화되어 채택 불가.

### 조치
- 변경 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`

### 다음 시작점
1. foreign-only include bucket 내부를 actor/guid 단위로 재분해(특히 `1D41`/`64AC` 분리)
2. dual/single 동시 억제가 아니라 한 bucket(한 actor 축)만 타격하는 1가설 설계
3. 적용 즉시 gate + selected + rollup 재검증

## 2026-04-17 추가 실험 15 (진단 강화, production 동작 동일)

### 목적
- foreign-only include bucket을 actor/guid 단위로 분해해
  `1D41`와 `64AC`가 같은 레버를 공유하는지 확인.

### 변경
- `ActIngestionService` foreign-only probe key에 context 추가:
  - `source=<hex actorId>`
  - `sourceAction=<GUID>`
- 관련 decision record/계측 호출부 확장.

### 결과
- baseline/gate/selected/rollup 동일 유지:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - SAM `1D41=+1,027,920`
    - DRG `64AC=-13,398`
- heavy2 include 분해(금액 합):
  - `sourceAction=1D41`: `1,072,524`
  - `sourceAction=64AC`: `314,482`
  - `sourceAction=4094`: `42,678`

### 해석
- foreign-only include 레버는 heavy2에서 주로 `1D41`에 더 크게 작동.
- 같은 레버를 단순 억제하면 `1D41` 역행 리스크가 더 큼(실험 14와 정합).
- 다음 production 가설은 foreign-only 자체가 아니라,
  `64AC` 전용으로 분리 가능한 evidence 축(예: same-action dual lifecycle 내부)을 찾아야 함.

## 2026-04-17 추가 실험 16 (`foreign_source/action cardinality >=2` 완화, 기각/원복)

### 가설
- single foreign-only include 조건의 cardinality 하한을 `>=3`에서 `>=2`로 완화하면
  heavy2 `64AC` 경로가 일부 회복되면서 gate를 유지할 수 있다고 가정.

### 변경
- `resolveKnownSourceSingleTargetForeignOnlyTrackedSplit`
  - include guard를 아래처럼 완화:
    - `foreignSourceCount < 3 || foreignActionCount < 3`
    - → `foreignSourceCount < 2 || foreignActionCount < 2`

### 결과 (`>=2` 상태에서 측정)
- rollup/gate:
  - `mape=0.010944336939220084`
  - `p95=0.024935730088260492`
  - `max=0.03537628179947446`
  - `pass=true`
- heavy2 fight2:
  - SAM `1D41=+1,027,920` (변화 없음)
  - DRG `64AC=-13,398` (변화 없음)
- probe:
  - heavy2 fight2 includeAmount `759,669` (유지)
  - heavy4 fight5 includeAmount `0 -> 78,598` (신규 include 발생)
  - lindwurm fight8 includeAmount `0` (변화 없음)

### 조치
- heavy2 핵심 residual 개선이 없고 heavy4에 신규 include 부작용이 생겨
  explainable 이득 대비 리스크가 커서 기각.
- 조건을 즉시 `>=3`로 원복.

### 비고 (검증 환경 이슈 및 해소)
- 원복 직후에는 Gradle 캐시 jar 접근 오류(`AccessDeniedException`)와 장시간 실행으로
  재검증이 지연됐음.
- 권한 확장 + 장시간 실행으로 최종 재검증 완료:
  - `ActIngestionServiceTest`: `failures=0`, `errors=0`
  - `SubmissionParityRegressionGateTest`: `failures=0`, `errors=0`
  - `SubmissionParityReportDiagnostics`:
    - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
    - heavy2 fight2: `1D41=+1,027,920`, `64AC=-13,398`
    - probe includeAmount: heavy2 `759,669`, heavy4 `0`, lindwurm `0`
- 현재 남은 production 변경은 실험 13/15의 비침습 probe 계측이다.

## 2026-04-17 추가 실험 17 (`64AC recent-exact 강제 단일배정`, 기각/원복)

### 가설
- heavy2 fight2에서 `recentExact=64AC` raw tick이 `1D41/4094/409C`로 교차 분배되는
  shared GUID contamination 축을 줄이면
  `64AC`/`1D41` 동시 개선 가능하다고 가정.

### 변경
- `resolveKnownSourceTwoTargetWeightedTrackedTargetSplit`에서
  아래 조건을 만족하면 weighted 분배 대신 source `64AC` 단일 배정:
  - `recentExactActionId == 64AC`
  - `activeTargets == 2`
  - `recentSourceActionId == recentExactActionId`
  - foreign distinct action >= 3
  - foreign 측에 `64AC` action 없음

### 결과
- heavy2 selected:
  - DRG `64AC`: `-13,398 -> +517,646` (대폭 악화)
  - SAM `1D41`: `+1,027,920 -> +925,938` (개선)
- rollup/gate:
  - `mape=0.013690378925298938`
  - `p95=0.035045637795188614`
  - `max=0.046861608515342004`
  - `pass=false` (gate fail)
- 참고 진단:
  - `heavy2.fight2.drg weightedActionRecipients`에서 `assignedAction=64AC` 비중은 증가했지만
    잔여 교차분배가 남고 총량 과상승 발생.

### 조치
- gate fail + `64AC` 대폭 악화로 즉시 기각, 코드 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `64AC=-13,398`
    - `1D41=+1,027,920`
  - foreign-only probe totals:
    - heavy2 includeAmount `759,669`
    - heavy4 includeAmount `0`
    - lindwurm includeAmount `0`

### 해석
- `recentExact=64AC` 교차분배 축은 실제로 존재하지만,
  해당 구간을 단일배정으로 강제하면 total balance를 깨고 전역 gate를 훼손함.
- 다음 가설은 단일배정이 아니라, `64AC`/`1D41` 간 교차분배를 제한하되
  동일 raw tick의 총량 보존을 깨지 않는 방향(부분 가중치 조정)으로 좁혀야 함.

## 2026-04-17 추가 실험 18 (`64AC` 2타깃 구간 same-source 소량 가중치, 기각/원복)

### 가설
- 강제 단일배정 대신, `status0_weighted_tracked_target_split`에서
  `recentExact=64AC` + `activeTargets=2` + foreign action 다변(>=3) 구간에만
  same-source(=64AC) weight를 소량(`0.20`) 재주입하면
  heavy2 `64AC`/`1D41`를 완만하게 개선하면서 gate를 유지할 수 있다고 가정.

### 변경
- `resolveKnownSourceTwoTargetWeightedTrackedTargetSplit`에서
  기본 `KNOWN_SOURCE_TWO_TARGET_SAME_SOURCE_WEIGHT_FACTOR(0.00)` 대신
  조건 충족 시 `0.20`을 사용하도록 분기 추가.

### 결과
- heavy2 fight2:
  - DRG `64AC`: `-13,398 -> -642` (개선)
  - SAM `1D41`: `+1,027,920 -> +1,024,221` (소폭 개선)
- rollup/gate:
  - `mape=0.011039501997665195` (악화)
  - `p95=0.02569592128443344` (악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`
- selected 비교:
  - heavy4 fight5 DRG `64AC` `delta=707,783`로 baseline 대비 악화.

### 조치
- heavy2 소폭 개선 대비 heavy4/rollup 악화로 기각, 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `64AC=-13,398`
    - `1D41=+1,027,920`

### 해석
- `64AC` 교차분배 억제는 방향성은 맞지만, 현재 조건/강도로는
  heavy4와 전역 품질을 동시에 보존하지 못함.
- 다음 가설은 `64AC` 전용이더라도 전역 공통 조건을 건드리는 weight 분기보다,
  heavy2 특이 시그널(타깃 lifecycle 증거)과 결합된 더 좁은 evidence gate가 필요.

## 2026-04-17 추가 실험 19 (`64AC` signal-anchored 2타깃 same-source 가중치, 기각/원복)

### 가설
- 실험 18보다 더 좁게, 아래 조건에서만 same-source 가중치(`0.20`)를 적용하면
  heavy2 lifecycle 초반 오염만 줄이고 heavy4/lind 영향은 줄일 수 있다고 가정.
  - `recentExact=64AC`
  - `activeTargets==2`
  - `recentSourceAction==64AC`
  - foreign distinct action >= 3, foreign에 `64AC` 없음
  - 같은 target에 동일 source/action status signal이 최근 `1.5s` 이내 존재

### 변경
- `resolveKnownSourceTwoTargetWeightedTrackedTargetSplit`에
  signal-anchored 분기 추가 후 same-source factor를 조건부로 `0.20` 적용.

### 결과
- heavy2 fight2:
  - DRG `64AC`: `-13,398 -> -6,984` (개선)
  - SAM `1D41`: `+1,027,920 -> +1,026,163` (소폭 개선)
- rollup/gate:
  - `mape=0.01101538528960193` (악화)
  - `p95=0.025654415907600633` (악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`
- selected 영향:
  - heavy4 fight5 DRG `64AC` `delta=707,783`로 baseline 대비 악화.

### 조치
- heavy2 개선 대비 heavy4/rollup 악화로 기각, 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `64AC=-13,398`
    - `1D41=+1,027,920`

## 2026-04-18 추가 실험 20 (`64AC` weighted 교차의 foreign `1D41` 제거, 기각/원복)

### 가설
- heavy2 fight2 `1D41` 과대의 큰 축은
  `recentExact=64AC` raw tick이 `status0_weighted_tracked_target_split`에서
  foreign `1D41`로 교차 배정되는 경로이므로,
  이 후보를 제거하면 `1D41` 과대를 크게 줄일 수 있다고 가정.

### 변경
- `resolveKnownSourceTwoTargetWeightedTrackedTargetSplit`에서
  아래 조건 시 foreign `1D41` candidate 제거:
  - `recentExact=64AC`
  - `activeTargets==2`
  - `recentSourceAction=64AC`
  - foreign distinct action >= 3

### 결과
- heavy2 fight2:
  - `1D41`: `+1,027,920 -> +925,938` (개선)
  - `64AC`: `-13,398` (변화 없음)
- rollup/gate:
  - `mape=0.011363400023697562` (악화)
  - `p95=0.025618674693884166` (소폭 악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`

### 조치
- selected 개선 대비 전역 `mape` 악화가 커서 기각/원복.

## 2026-04-18 추가 실험 21 (`64AC` 교차 제거 + healer pair 동시 존재 조건, 기각/원복)

### 가설
- 실험 20의 부작용을 줄이기 위해,
  foreign action set에 `4094(Dia)`와 `409C(Biolysis)`가 동시에 있을 때만
  foreign `1D41` 제거를 적용하면 heavy2 특이 구간만 타격 가능하다고 가정.

### 변경
- 실험 20 조건 + `foreign actions ⊇ {4094, 409C}` 추가.

### 결과
- heavy2 fight2:
  - `1D41`: `+1,027,920 -> +937,389` (개선)
  - `64AC`: `-13,398` (변화 없음)
- rollup/gate:
  - `mape=0.011295773272889198` (악화)
  - `p95=0.025618077288229717` (소폭 악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`

### 조치
- 전역 `mape` 악화로 기각/원복.
- 원복 후 baseline 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `64AC=-13,398`
    - `1D41=+1,027,920`

## 다음 턴 바로 시작 체크포인트 (2026-04-18)

### 현재 상태
- 실험 20/21은 모두 기각되어 production 동작은 baseline으로 복귀.
- 현재 작업트리 변경 파일:
  - `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java` (진단/probe 계측 유지 상태)
  - `src/test/java/com/bohouse/pacemeter/application/SubmissionParityReportDiagnostics.java` (진단 테스트/출력 유지 상태)
  - `tasks.md` (실험 로그 업데이트)

### 재시작 순서
1. baseline 재확인
   - `ActIngestionServiceTest`
   - `SubmissionParityRegressionGateTest`
2. selected 재확인
   - heavy2 fight2 `64AC` / `1D41`
   - heavy4 fight5 `64AC`
   - lindwurm fight8 `64AC`
3. 가설 선택 원칙
   - `weighted` 경로에서 action 후보 제거/강제배정 계열은 보류
   - 다음은 `fallback/tracked_target_split` evidence gate 1개만 시도
4. 적용 즉시 재검증
   - rollup + selected + gate

## 2026-04-18 추가 실험 22 (`foreign_only_single_target` snapshot weighted 우선, 기각/원복)

### 가설
- `status0_tracked_target_split_foreign_only_single_target`는 현재 equal split이라
  근거 없는 임의 분배가 남는다.
- dual 경로처럼 snapshot weighted 분배를 우선 적용하면
  heavy2 `1D41/64AC` 오염을 줄이면서 heavy4/lind 영향은 제한될 수 있다고 가정.

### 변경
- `ActIngestionService.emitDotDamage`의 `singleTargetForeignOnlyTrackedDots` 분기에서
  `resolveSnapshotWeightedTrackedSubsetAllocations(...)`를 먼저 시도.
- 성공 시 신규 mode `status0_weighted_tracked_target_split_foreign_only_single_target`로 배정,
  실패 시 기존 equal split fallback 유지.

### 결과
- baseline/gate:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - rollup: `mape=0.010943409925028494`, `p95=0.02555281693406692`, `max=0.03537628179947446`, `pass=true`
- selected:
  - heavy2 fight2
    - SAM `1D41`: `+1,027,920 -> +1,049,499` (악화)
    - DRG `64AC`: `-13,398 -> -44,816` (악화)
  - heavy4 fight5 DRG `64AC`: `+707,783` (동일)
  - lindwurm fight8 DRG `64AC` surface: `delta=20,776` (동일)
- probe:
  - foreign-only include totals는 baseline과 동일
    (`heavy2 includeAmount=759,669`, `heavy4/lind includeAmount=0`)
  - 신규 weighted-single mode가 heavy2에서 실제 발동됨
    - `1D41 amount=43,883`
    - `64AC amount=55,460`

### 조치
- heavy2 핵심 residual 동시 악화로 기각, 즉시 원복.
- 원복 후 복귀 재확인(권한 확장 재실행 포함):
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `1D41=+1,027,920`
    - `64AC=-13,398`

## 2026-04-18 전환 결정 기록 (`dot 사용 이력` evidence 축)

### 전환 이유
- 기존 `suppress/weight/cardinality` 미세 조정은 반복 탐색 대비 실효가 낮음.
  - `known-source tracked_target_split` suppress 실효: heavy2에서도 `0.68%` 수준.
  - selected 개선 시 heavy4/lindwurm/rollup 역행이 반복됨.
- 반면 `status=0` 병목은 “누가 썼는지”보다
  `source-target-action` 시간축 근거 부재에서 발생하므로,
  다음 레버는 `dot 사용 이력(apply/remove/최근 evidence)` 기반으로 옮기는 것이 합리적.

### 근거
- heavy2 fight2 핵심 잔차는 여전히
  - `SAM 1D41 = +1,027,920`
  - `DRG 64AC = -13,398`
- 큰 질량 구간:
  - `knownSourceTrackedTargetSplitProbe amount=4,758,059`
  - `knownSourceForeignOnlySplitProbe includeAmount=759,669` (heavy4/lind는 0)
- 즉, 전역 clamp보다 evidence 복원 축이 레버리지/설명력 모두 높음.

## 2026-04-18 추가 실험 23 (`single foreign-only`에 source-target usage evidence gate, 무효/원복)

### 가설
- `status0_tracked_target_split_foreign_only_single_target` include 직전에
  동일 `source-target-action` 최근 사용 근거가 있으면 include를 막아
  heavy2 오염을 줄일 수 있다고 가정.

### 변경
- `resolveKnownSourceSingleTargetForeignOnlyTrackedSplit`에
  source-target 최근 usage evidence(action/status application + source evidence) 체크를 추가.
- 근거가 있으면 probe reason `source_target_usage_present`로 제외.

### 결과
- baseline/gate 통과.
- 그러나 selected/probe/rollup 모두 baseline과 완전 동일:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`
  - foreign-only include totals 동일(`heavy2=759,669`, heavy4/lind=0)
- `source_target_usage_present` bucket 실발동이 없어 실효 커버리지 0으로 판단.

### 조치
- 복잡도 증가 대비 효과가 없어 즉시 원복.
- production 코드는 baseline 상태 유지.

## 2026-04-18 추가 실험 24 (`single foreign-only` source lifecycle fresh gate, 기각/원복)

### 가설
- `status0_tracked_target_split_foreign_only_single_target`에서
  same-source tracked DoT의 만료가 아직 충분히 남아 있으면
  (`expiresAt > now + 4s`) foreign-only include를 막아
  `dot 사용 이력` 근거로 오염을 줄일 수 있다고 가정.

### 변경
- `resolveKnownSourceSingleTargetForeignOnlyTrackedSplit`에
  `source_lifecycle_fresh` 제외 조건 추가:
  - `sourceTracked=1` + `recentSourceAction 일치` 이후
  - same-source tracked dot lifecycle이 fresh면 include 금지.

### 결과
- baseline/gate는 통과했지만 전역/selected 동시 악화:
  - rollup:
    - `mape=0.011318050284553606` (악화)
    - `p95=0.025596116284919656` (소폭 개선)
    - `max=0.03537628179947446` (동일)
    - `pass=true`
  - heavy2 fight2:
    - SAM `1D41`: `+1,027,920 -> +1,088,941` (악화)
    - DRG `64AC`: `-13,398 -> -16,959` (악화)
- probe 변화:
  - heavy2 foreign-only includeAmount `759,669 -> 403,479`로 감소
  - 제외 reason `source_lifecycle_fresh` 신규 발생
    - `1D41 amount=289,279`
    - `64AC amount=66,911`
- 해석:
  - include 감소 자체는 성공했지만, 제거된 질량이 `status0_tracked_target_split` 계열로 이동하며
    heavy2 핵심 residual이 오히려 악화.

### 조치
- selected/rollup 동시 악화로 즉시 기각 및 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `1D41=+1,027,920`
    - `64AC=-13,398`

## 2026-04-19 추가 실험 25 (`foreign-only` 후보 usage-evidence 필터, 기각/원복)

### 가설
- include 자체를 줄이는 대신, `foreign-only`로 진입한 뒤
  후보 foreign tracked dot을 `source-target-action 최근 usage evidence`로 필터링하면
  잘못된 재배치를 줄이면서 heavy2 `64AC/1D41`를 동시에 개선할 수 있다고 가정.

### 변경
- `resolveKnownSourceDualTargetForeignOnlyTrackedSplit`
- `resolveKnownSourceSingleTargetForeignOnlyTrackedSplit`
  - foreign 후보 목록 생성 후
    `filterForeignOnlyCandidatesByTargetUsageEvidence(...)` 적용
  - 필터 후 후보가 `>=2`이면 필터된 집합 사용, 아니면 기존 집합 유지.
- helper:
  - `hasRecentUsageOnTarget(targetId, sourceId, actionId, cutoff)`
  - action/status application + source evidence를 함께 확인.

### 결과
- baseline/gate는 통과.
- selected:
  - heavy2 fight2 DRG `64AC`: `-13,398 -> -10,108` (개선)
  - heavy2 fight2 SAM `1D41`: `+1,027,920 -> +1,029,179` (악화)
  - heavy4 fight5 DRG `64AC`: `+707,783` (동일)
  - lindwurm fight8 DRG surface: `delta=20,776` (동일)
- rollup/gate:
  - `mape=0.011016570814692213` (악화)
  - `p95=0.025638245870164406` (악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`
- foreign-only include totals/probe는 baseline과 실질 동일(`includeAmount=759,669`).

### 해석
- 후보 usage-evidence 필터는 일부 `64AC`에는 이득을 줬지만,
  `1D41`/전역 품질을 동시에 보존하지 못함.
- 특히 include 총량이 거의 안 변해, 핵심 오염 경로 질량을 충분히 재편하지 못함.

### 조치
- rollup 악화 + selected 동시 개선 실패로 기각, 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `1D41=+1,027,920`
    - `64AC=-13,398`

## 2026-04-19 추가 실험 26 (`single foreign-only` snapshot same-source weight gate 재검증, 기각/원복)

### 가설
- `single foreign-only include`에서
  현재 타깃 snapshot에 same-source weight가 충분히 남아있으면(`>=0.40`)
  foreign-only를 막으면 전이 말단 과분리를 줄일 수 있다고 가정.

### 변경
- `resolveKnownSourceSingleTargetForeignOnlyTrackedSplit`에
  `same_source_snapshot_present` 제외 조건 추가:
  - `snapshot.weights[(source, recentSourceAction)] >= 0.40`이면 include 금지.

### 결과
- baseline/gate는 통과했지만 실험 24와 동일 패턴으로 악화:
  - heavy2 foreign-only includeAmount `759,669 -> 403,479` 감소
  - heavy2 fight2:
    - SAM `1D41`: `+1,027,920 -> +1,088,941` (악화)
    - DRG `64AC`: `-13,398 -> -16,959` (악화)
  - rollup:
    - `mape=0.011318050284553606` (악화)
    - `p95=0.025596116284919656` (소폭 개선)
    - `max=0.03537628179947446` (동일)
    - `pass=true`
- heavy4/lind selected는 표면상 동일이나,
  heavy4에도 `same_source_snapshot_present` 제외 bucket이 신규 관측됨(`amount=78,598`).

### 해석
- include 자체를 줄이는 축은 재현 가능하지만,
  줄어든 질량이 다른 분배 경로로 이동해 heavy2 핵심 residual/rollup을 동시에 악화.
- 동일 가설군(실험 24/26)은 현재 구조에서 비채택으로 확정.

### 조치
- 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `1D41=+1,027,920`
    - `64AC=-13,398`

## 2026-04-20 추가 실험 27 (`single foreign-only` mixed reallocation with same-source, 기각/원복)

### 가설
- suppress가 아니라 `single foreign-only` 분기 내부에서
  same-source를 후보에 재포함한 mixed split을 적용하면,
  질량이 다른 경로로 튀는 부작용 없이 heavy2 핵심 residual을 줄일 수 있다고 가정.

### 변경
- 신규 helper 추가:
  - `resolveKnownSourceSingleTargetMixedForeignOnlyAllocations(...)`
  - 조건: `foreign>=2`, `sourceTracked=1`, snapshot fresh.
- 분배 방식:
  - foreign 후보 snapshot weight를 사용
  - same-source snapshot weight를 재포함
  - same-source share를 `[0.20, 0.60]`으로 clamp 후 planner 배분.
- 적용 위치:
  - `emitDotDamage`의 `singleTargetForeignOnlyTrackedDots` 분기에서
    mixed allocation 우선 시도, 실패 시 기존 equal split fallback.

### 결과
- baseline/gate는 통과.
- 하지만 selected/rollup 동시 악화:
  - heavy2 fight2:
    - SAM `1D41`: `+1,027,920 -> +1,157,877` (대폭 악화)
    - DRG `64AC`: `-13,398 -> -54,825` (악화)
  - rollup:
    - `mape=0.011503908024723827` (악화)
    - `p95=0.02555281693406692` (소폭 개선)
    - `max=0.03537628179947446` (동일)
    - `pass=true`
- mode 발동 확인:
  - `status0_mixed_tracked_target_split_foreign_only_single_target`가
    heavy2 `1D41/64AC`에서 실발동.

### 해석
- “같은 분기 안에서 재배치” 접근도 현재 조건/클램프에서는
  heavy2 핵심 residual을 동시에 악화시킴.
- mixed 재배치 자체는 가능하지만, 현재 구현은 same-source 재주입량이 과해
  `1D41` 과대 축을 증폭.

### 조치
- 즉시 기각/원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `1D41=+1,027,920`
    - `64AC=-13,398`

## 2026-04-20 추가 실험 28 (`tracked_target_split` single-target snapshot-weighted fallback, 무효/원복)

### 가설
- 대질량 bucket(`tracked_target_split` ineligible/sourceTracked=0/activeTargets=1)을
  직접 타격하기 위해,
  generic `tracked_target_split` 진입 직전에 single-target snapshot-weighted fallback을 넣으면
  heavy2 오염을 줄일 수 있다고 가정.

### 변경
- `emitDotDamage`에서 `singleTargetForeignOnly` 다음, equal `tracked_target_split` 직전에
  아래 조건의 snapshot-weighted fallback 분기 추가:
  - `acceptedBySource`, `status=0`, `activeTargets==1`,
  - `trackedDots>=4`, `sourceTrackedDots.isEmpty()`,
  - `recentExact=null`
  - 분기 mode: `status0_snapshot_weighted_tracked_target_split_single_target`

### 결과
- baseline/gate/selected/probe가 baseline과 완전히 동일(무효):
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2: `1D41=+1,027,920`, `64AC=-13,398`
  - 신규 mode `status0_snapshot_weighted_tracked_target_split_single_target` 발동 0
- 해석:
  - 해당 위치/조건에서는 실제로 분기가 타지 않거나 후보가 형성되지 않아 실효 커버리지 0.

### 조치
- 코드 복잡도만 증가시키므로 즉시 원복.
- 원복 후 baseline(`ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`) 재확인 pass.

## 2026-04-20 추가 실험 29 (`ineligible/sourceTracked=0/activeTargets=1` 버킷 분리 리팩토링 시도, 무효/원복)

### 가설
- heavy2 대질량 bucket
  (`status0_tracked_target_split`의 `ineligible + sourceTracked=0 + activeTargets=1 + trackedTargets>=4`)
  을 전용 mode로 분리하고 evidence-weighted 분배를 적용하면
  FFLogs companion live rDPS에 더 가까워질 수 있다고 가정.

### 변경
- 시도한 구조:
  - 버킷 판별 helper
  - 버킷 전용 weighted allocation helper(action/status application, recent signal, expiresAt 기반 가중)
  - mode `status0_ineligible_single_target_weighted_split`로 emit
- 적용 위치:
  - `tracked_target_split` equal split 직전.

### 결과
- baseline/gate/selected/probe가 baseline과 완전히 동일(무효):
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`
  - 신규 mode `status0_ineligible_single_target_weighted_split` 발동 0
- 해석:
  - 현재 위치/조건에서는 실제 런타임 커버리지가 없었음.
  - “버킷 분리” 방향은 맞지만, 이 삽입 지점에서는 해당 버킷을 잡지 못함.

### 조치
- 실효 0 + 코드 복잡도 증가로 즉시 원복.
- 원복 후 baseline(`ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`) 재확인 pass.

## 2026-04-20 추가 실험 30 (`guid-missing single-target` guard 이전 버킷 분리 + evidence fallback, 무효/원복)

### 가설
- 기존 버킷 분기 미발동 원인은 `suppressKnownSourceGuidMissingFallback` guard 뒤에 있어서라고 보고,
  guard 이전에서 `single-target + sourceTracked=0 + tracked>=4 + recentExact=null` 버킷을 먼저 잡으면
  실효 커버리지가 생길 것이라고 가정.

### 변경
- guard 이전 pre-hook 추가:
  - `shouldUseGuidMissingSingleTargetWeightedBucket(...)`
  - 우선 `resolveSnapshotWeightedTrackedSubsetAllocations(...)`
  - snapshot 실패 시 `resolveGuidMissingSingleTargetEvidenceAllocations(...)`로 fallback
  - mode 후보:
    - `status0_guid_missing_single_target_weighted_split`
    - `status0_guid_missing_single_target_evidence_weighted_split`

### 결과
- baseline는 통과.
- selected/rollup 수치는 baseline과 완전 동일:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`
- 신규 mode 두 개 모두 발동 0(실효 커버리지 없음).

### 해석
- guard 위치를 옮겨도 해당 버킷이 이 지점에서 실질적으로 형성되지 않음.
- “버킷 분리 방향” 자체는 맞지만, 현재 분기 트리에서 타격 지점이 여전히 틀림.

### 조치
- 코드 복잡도만 증가하므로 즉시 원복.
- 원복 후 baseline(`ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`) 재확인 pass.

## 2026-04-20 리팩토링 체크포인트 31 (`emitDotDamage` 분기 추출, 동작 불변 확인)

### 전환 이유/근거
- 최근 실험 28~30이 모두 `mode fire=0`으로 무효였고, 동일 가설군 반복으로는 병목 분기를 제대로 타격하지 못함.
- 원인은 파라미터가 아니라 `status=0` 분기 트리의 guard/순서/조기 return 결합으로 판단.
- 따라서 다음 단계는 규칙 튜닝보다 먼저, 분기 책임을 헬퍼 단위로 분리해
  “어디서 실제로 떨어지는지”를 좁힐 수 있는 구조로 정리하는 것이 합리적.

### 변경
- `ActIngestionService.emitDotDamage` 내부 인라인 분기를 아래 헬퍼로 추출:
  - `tryEmitStatus0CorroboratedKnownSource`
  - `tryEmitStatus0SnapshotRedistribution`
  - `tryEmitStatus0UnacceptedSourceTrackedSplit`
  - `tryEmitStatus0KnownSourceTrackedRouting`
  - `tryEmitStatusAcceptedBySource`
  - `tryEmitStatus0FallbackTrackedSplit`
- 분기 순서/조건/모드명/분배 수식은 기존과 동일 유지(구조 분리만 수행).

### 검증
- baseline tests:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected + rollup diagnostics:
  - heavy2 fight2 SAM `1D41`: `+1,027,920` (동일)
  - heavy2 fight2 DRG `64AC`: `-13,398` (동일)
  - heavy4 fight5 DRG `64AC`: `+707,783` (동일)
  - lindwurm fight8 DRG surface: `delta=20,776` (동일)
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true` (동일)

### 결론
- 리팩토링 전환은 채택.
- 다음 변경은 이 구조 위에서 “실제로 발동하는 단일 버킷” 1개만 대상으로 진행.

## 2026-04-20 추가 실험 32 (`status0_fallback_tracked_target_split` single-target known-source>=4 suppress, 무효/원복)

### 현재 관찰
- mode/probe 재확인:
  - heavy2 fight2 SAM `1D41`은 `status0_tracked_target_split` + `status0_fallback_tracked_target_split`가 주요 과대 축.
  - heavy4 fight5 DRG `64AC`는 fallback 비중이 거의 없고 `snapshot_redistribution + tracked_target_split` 중심.

### 가설
- `unknown status=0`에서 `known-source + activeTargets=1 + trackedTargets>=4` fallback만 차단하면
  heavy2 과대를 줄이고 heavy4/lind 영향은 작을 수 있다고 가정.

### 수정 범위
- `tryEmitStatus0FallbackTrackedSplit(...)` 초기에 아래 조건이면 `return`:
  - `unknownStatusDot`
  - `sourceId` known party
  - `countTrackedTargetsWithActiveDots()==1`
  - `trackedDots.size()>=4`

### 검증 결과
- selected + rollup + gate 결과가 baseline과 완전 동일(실효 0):
  - heavy2 fight2 SAM `1D41`: `+1,027,920` (동일)
  - heavy2 fight2 DRG `64AC`: `-13,398` (동일)
  - heavy4 fight5 DRG `64AC`: `+707,783` (동일)
  - lindwurm fight8 DRG surface: `delta=20,776` (동일)
  - rollup: `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true` (동일)
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass

### 남은 리스크/조치
- 해당 조건은 실제 fallback 발동 구간과 불일치해 커버리지 0으로 판단.
- 즉시 원복 완료.

## 2026-04-20 추가 실험 33 (`known-source single-target no-exact` weighted split, 기각/원복)

### 현재 관찰
- `ineligible+activeTargets=1` 버킷을 직접 줄이기 위해
  `tracked_target_split` 직전에 `snapshot-weighted` 우선 분기를 넣어 실효 커버리지를 확인.

### 가설
- `accepted known-source + activeTargets=1 + tracked>=4 + recentExact=null` 구간은
  equal split보다 snapshot-weighted 분배가 오염을 줄여 heavy2 residual을 줄일 수 있다고 가정.

### 수정 범위
- `tryEmitStatus0KnownSourceTrackedRouting`에 분기 추가:
  - helper `resolveKnownSourceSingleTargetNoExactWeightedTrackedTargetSplit(...)`
  - mode: `status0_weighted_tracked_target_split_single_target_no_exact`

### 검증 결과
- gate/rollup은 통과 및 소폭 개선:
  - `mape=0.01099019057459621`, `p95=0.025094314520958304`, `max=0.03537628179947446`, `pass=true`
- 하지만 heavy2 핵심 residual 변화 없음:
  - heavy2 fight2 SAM `1D41=+1,027,920` (동일)
  - heavy2 fight2 DRG `64AC=-13,398` (동일)
- 신규 mode 발동은 heavy2가 아니라 heavy4에서만 소량 관측:
  - heavy4 `status0_weighted_tracked_target_split_single_target_no_exact` amount `4,651` (1 hit)
  - heavy4 target delta `+707,783 -> +692,784` (heavy2 병목과 무관한 변화)

### 남은 리스크/조치
- 목표(heavy2 병목 해소) 대비 타격 지점이 어긋났다고 판단.
- 복잡도 증가만 남기지 않기 위해 즉시 원복.
- 원복 후 baseline (`ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`) pass 재확인.

## 2026-04-20 추가 실험 34 (`single-target recent-exact` source-anchor, gate fail/원복)

### 현재 관찰
- heavy2 `ineligible + activeTargets=1 + tracked>=4`에서
  foreign action(특히 `1D41`)로 누수되는 분배를 직접 줄이기 위해
  equal split 대신 source-action 앵커링을 시도.

### 가설
- `known-source + recentExact + sourceTracked 단일일치 + activeTargets=1 + tracked>=4`이면
  source-action으로 전량 앵커링해 cross-job 누수를 줄일 수 있다고 가정.

### 수정 범위
- `tryEmitStatus0KnownSourceTrackedRouting`에 아래 분기 추가:
  - `resolveKnownSourceSingleTargetRecentExactAnchor(...)`
  - mode: `status0_single_target_recent_exact_source_anchor`
  - 조건 충족 시 `dot.damage()` 전량을 source/action으로 emit.

### 검증 결과
- 가설은 강하게 과교정되어 gate fail:
  - rollup: `mape=0.012832537116896624`, `p95=0.031244145274622596`, `max=0.03537628179947446`, `pass=false`
- selected 악화:
  - heavy2 fight2 SAM `1D41`: `+1,027,920 -> +1,180,589` (악화)
  - heavy2 fight2 DRG `64AC`: `-13,398 -> +97,232` (악화)
  - heavy4 fight5 DRG `64AC`: `+707,783 -> +846,216` (악화)
- mode 발동:
  - heavy2 `1D41`: `status0_single_target_recent_exact_source_anchor` 다수 발동
  - heavy2 `64AC`: same mode 발동(`amount=226,768`)
  - heavy4 `64AC`: same mode 발동(`amount=184,579`)

### 조치
- 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`

## 2026-04-20 추가 실험 35 (`64AC` single-target split에서 foreign `1D41` 제외, 기각/원복)

### 현재 관찰
- heavy2 DRG evidence에서 `activeTargets=1`, `tracked>=4`일 때 tracked set에 `1D41`이 동반되어
  `64AC` split 일부가 SAM으로 누수되는 패턴이 반복 관측.
- 같은 조건에서 heavy4/lind는 `1D41` 동반이 거의 없어 영향 분리 가능성이 있다고 판단.

### 가설
- `recentExact=64AC` 단일타깃 구간에서 foreign `1D41`만 split 후보에서 제외하면
  heavy2 `1D41` 과대와 `64AC` 오차를 동시에 줄일 수 있다고 가정.

### 수정 범위
- `tryEmitStatus0KnownSourceTrackedRouting`에 전용 분기 추가:
  - helper `resolveKnownSourceSingleTargetExcludedForeignRecentExactTrackedDots(...)`
  - 조건:
    - known-source, `status=0`
    - `activeTargets==1`, `tracked>=4`, `sourceTracked==1`
    - `recentExact==64AC` and foreign tracked에 `1D41` 존재
  - mode: `status0_tracked_target_split_excluding_foreign_recent_exact_action`

### 검증 결과
- heavy2 selected는 개선:
  - heavy2 fight2 DRG `64AC`: `-13,398 -> +5,499`
  - heavy2 fight2 SAM `1D41`: `+1,027,920 -> +971,226`
- heavy4/lind selected는 수치 변화 거의 없음:
  - heavy4 fight5 DRG `64AC`: `+707,783` (동일)
  - lindwurm fight8 DRG surface: `delta=20,776` (동일)
- 하지만 전역 rollup 악화:
  - `mape=0.01106431353441662` (악화)
  - `p95=0.02574741581083996` (악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`

### 조치
- selected 개선 대비 전역 품질 악화로 기각.
- 즉시 원복 후 baseline (`ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`) pass 재확인.

## 2026-04-20 추가 실험 36 (`second application` 조건 결합한 foreign `1D41` 제외, 기각/원복)

### 현재 관찰
- 웹 리서치 제안(“second application 전 split 금지”)을
  기존 실험 35의 편향 조건(`64AC + foreign 1D41`)에 결합해 적용 범위를 더 줄였음.

### 가설
- `recentExact=64AC` 케이스에서 source/action의 최근 distinct application target이 2개 미만이면
  multi-target 전이 증거가 부족하다고 보고 foreign `1D41` 제외 split을 적용하면
  heavy2는 유지 개선하면서 rollup 악화를 줄일 수 있다고 가정.

### 수정 범위
- helper 추가:
  - `countRecentDistinctApplicationTargetsForSourceAction(...)`
  - `resolveKnownSourceSingleTargetExcludedForeignRecentExactTrackedDots(...)`에 second-application 조건 결합
- mode:
  - `status0_tracked_target_split_excluding_foreign_recent_exact_action`

### 검증 결과
- 결과가 실험 35와 동일:
  - rollup: `mape=0.01106431353441662`, `p95=0.02574741581083996`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2:
    - `64AC=-13,398 -> +5,499` (개선)
    - `1D41=+1,027,920 -> +971,226` (개선)
  - heavy4/lind selected 표면 변화 없음
- 즉, second-application 조건이 실제 발동 집합을 좁히지 못해 전역 악화 문제도 그대로 유지.

### 조치
- rollup 악화 유지로 기각, 즉시 원복.
- 원복 후 baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`

## 2026-04-20 재설계 5단계 (기각/원복)

### 가설
- `status0_tracked_target_split` 직전,
  - `known-source && status=0 && activeTargets=1 && tracked>=4 && sourceTracked=1`
  - `recentSource == recentExact == sourceTrackedAction`
  인 경우에 한해 최근 source-target-action 증거가 있는 tracked subset으로 split 축소하면
  heavy2의 단일타겟 전이 오염을 줄일 수 있다고 가정.

### 관찰
- assignment bucket 진단:
  - heavy2 핵심 버킷
    - `activeTargets=1|trackedTargets=4+|sourceTracked=1|sourceTrackedAction=64AC|recentSource=64AC|recentExact=64AC|foreignTracked=3|foreignActions=3|foreignActionSet=1D41,4094,409C`
    - `amount 226,768 -> 78,469`로 감소 확인
- selected fight:
  - heavy2 fight2 `1D41`: `+1,027,920 -> +1,026,253` (미미 개선)
  - heavy2 fight2 `64AC`: `-13,398 -> +14,197` (악화)
  - heavy4 fight5 `64AC`: `+707,783 -> +730,093` (악화)

### 회귀
- rollup:
  - `mape=0.011185229934315097` (기준선 `0.010996830747457277` 대비 악화)
  - `p95=0.02658301799454033` (악화)
  - `max=0.03537628179947446` (동일)
  - gate는 `pass=true`

### 결론
- 문제 버킷 감소 자체는 확인됐지만,
  selected/rollup 동시 개선을 만들지 못해 기각.
- production 로직은 원복.
- baseline 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`
  - heavy4 fight5 `64AC=+707,783`
  - lindwurm fight8 DRG surface `delta=20,776`

## 2026-04-20 재설계 6단계 (기각/원복)

### 가설
- `single-target foreign-only split`에서 `recent_exact_present`로 막히는 경우라도,
  같은 source가 직전에 foreign-only include를 이미 경험했으면(최근 15초)
  전이 구간으로 보고 include를 허용하면 heavy2 오염을 줄일 수 있다고 가정.

### 관찰
- 분리축 자체는 의도대로 동작:
  - heavy2 only에서 include 증가
    - `includeHits 22 -> 27`
    - `includeAmount 759,669 -> 934,635`
  - heavy4/lind는 include 0 유지.
- 하지만 selected fight residual은 역행:
  - heavy2 fight2 `64AC`: `-13,398 -> -57,138` (절대 오차 악화)
  - heavy2 fight2 `1D41`: `+1,027,920 -> +1,042,501` (악화)

### 회귀
- rollup은 소폭 개선됐지만(selected 목적과 충돌):
  - `mape=0.01095206861005285`
  - `p95=0.02555281693406692`
  - gate `pass=true`

### 결론
- heavy2 핵심 residual을 줄이는 목적에 반해 selected가 악화되어 기각.
- production 원복 완료.
- 원복 후 기준선 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`

## 2026-04-20 재설계 7단계 (기각/원복)

### 가설
- 문제 버킷(`activeTargets=1`, `tracked>=4`, `sourceTracked=1`, `recentSource==recentExact==sourceTrackedAction`)에서
  기본 `status0_tracked_target_split`의 균등 분배를
  `snapshot weighted` 분배로 치환하면 cross-shift를 줄일 수 있다고 가정.

### 관찰
- selected fight:
  - heavy2 fight2 `64AC`: `-13,398 -> -46,713` (절대 오차 악화)
  - heavy2 fight2 `1D41`: `+1,027,920 -> +1,107,576` (악화)
  - heavy4 fight5 `64AC`: `+707,783 -> +686,131` (개선)
- lindwurm DRG surface는 동일(`delta=20,776`).

### 회귀
- gate: `pass=true`
- rollup:
  - `mape=0.011175248787785539` (기준선 대비 악화)
  - `p95=0.024743941860938923` (개선)
  - `max=0.03537628179947446` (동일)

### 결론
- heavy2 핵심 residual과 rollup mape가 동시에 악화되어 기각.
- production 원복 완료, 기준선 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`

## 2026-04-20 재설계 8단계 (기각/원복)

### 가설
- `status0_fallback_tracked_target_split`에서
  `known party source && unknownStatus && shouldAcceptDot=false && activeTargets>1`이면
  비근거 fallback split으로 보고 suppress하면 heavy2 오염을 줄일 수 있다고 가정.

### 관찰
- heavy2 selected는 크게 개선:
  - `1D41: +1,027,920 -> +699,098`
  - `64AC: -13,398 -> -171,834`
- 하지만 전역 회귀가 크게 악화.

### 회귀
- rollup:
  - `mape=0.013699352394739761`
  - `p95=0.03562296151972086`
  - `max=0.0404580194569956`
  - `gate pass=false`

### 결론
- 전역 gate 실패로 기각/원복.

## 2026-04-20 재설계 9단계 (기각/원복)

### 가설
- 재설계 8의 suppress 범위를 `activeTargets>2`로 축소하면
  heavy2 이득 일부 유지 + gate 보존이 가능하다고 가정.

### 관찰
- gate는 보존되었으나 heavy2 이득이 매우 작음:
  - `1D41: +1,027,920 -> +1,010,424` (소폭 개선)
  - `64AC: -13,398` (동일)
- rollup `mape`는 기준선 대비 악화.

### 회귀
- rollup:
  - `mape=0.011198005458555019` (기준선 `0.010996830747457277` 대비 악화)
  - `p95=0.02561240833880624` (유사)
  - `max=0.03537628179947446` (동일)
  - gate `pass=true`

### 결론
- selected 개선 대비 전역 mape 악화로 기각/원복.
- 원복 후 기준선 복귀 확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`

## 2026-04-20 진단 업데이트 (유지)

### 반영
- `knownSourceTrackedTargetSplitAssignment` 계측 키에 `targetDeathAge` 추가.

### 관찰
- heavy2/heavy4/lind 상위 문제 버킷에서 `targetDeathAge=na`만 관측.
- 해석: 현재 핵심 잔차는 target death 직후 ghost tick 축이 아님.

## 2026-04-20 재설계 10단계 (기각/원복)

### 가설
- `status0_tracked_target_split`의 핵심 문제 버킷
  (`activeTargets=1`, `tracked>=4`, `sourceTracked=1`, `recentSource==recentExact==sourceTrackedAction`)에서만,
  foreign recipient를 최근 source-target-action 근거가 있는 항목으로 필터링하면
  heavy2 오염을 줄이면서 전역 영향은 제한될 것이라고 가정.

### 관찰
- 문제 버킷 감소:
  - `amount 226,768 -> 78,469` (heavy2 `foreignActionSet=1D41,4094,409C`)
- selected/전역은 악화:
  - heavy2 `1D41: +1,027,920 -> +1,026,250` (미미 개선)
  - heavy2 `64AC: -13,398 -> +14,199` (악화)
  - heavy4 fight5 `64AC: +707,783 -> +730,091` (악화)
  - rollup `mape=0.011185221134198205`, `p95=0.026582960062029405` (기준선 대비 악화)
  - gate는 `pass=true`

### 결론
- 버킷 자체는 줄였지만 selected/rollup 동시 개선 실패로 기각.
- production 원복 완료, 기준선 복귀 재확인.

## 2026-04-20 재설계 11단계 (기각/원복)

### 가설
- `status0_fallback_tracked_target_split`에서
  `known-party && unknownStatus && shouldAcceptDot=false && multi-target` 조건일 때,
  균등 분배 대신 `snapshot weighted` 분배를 우선 적용하면
  fallback 오염을 줄이면서 gate를 보존할 수 있다고 가정.

### 관찰
- heavy2 잔차가 크게 악화:
  - `1D41: +1,027,920 -> +1,144,850`
  - `64AC: -13,398 -> -61,803`
- heavy4/lind 표면은 대체로 동일.

### 회귀
- gate는 `pass=true` 유지.
- rollup:
  - `mape=0.011395559956833667` (기준선 대비 악화)
  - `p95=0.02555281693406692` (소폭 개선)
  - `max=0.03537628179947446` (동일)

### 결론
- heavy2 핵심 residual과 rollup mape 동시 악화로 기각.
- production 원복 완료.
- 원복 후 기준선 복귀 재확인:
  - rollup `mape=0.010996830747457277`, `p95=0.025614369256908187`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,027,920`, `64AC=-13,398`

## 2026-04-20 재설계 12단계 (기각/원복)

### 가설
- `status0_fallback_tracked_target_split`를 전면 치환하지 않고,
  기존 균등분배와 snapshot weighted 분배를 `alpha=0.25`로 블렌드하면
  heavy2 개선과 전역 안정성을 동시에 얻을 수 있다고 가정.

### 관찰
- heavy2 핵심 잔차는 악화:
  - `1D41: +1,027,920 -> +1,057,160`
  - `64AC: -13,398 -> -25,501`
- gate는 유지됐지만 전역 mape 악화:
  - `mape=0.011091592932796322` (기준선 대비 악화)
  - `p95=0.02555281693406692` (소폭 개선)

### 결론
- selected/rollup 동시 개선 실패로 기각.
- production 원복 완료 및 기준선 복귀 확인.

## 2026-04-21 세션 체크포인트 (403 중단 대비)

### 오늘 요약
- 403으로 세션이 자주 끊겨도, 로컬 테스트/진단은 계속 수행.
- 모든 실험은 "가설 1개 변경 -> gate/selected 재검증" 루프로 진행.
- 채택 가능한 개선(heavy2 개선 + gate/mape 유지)은 아직 없음.

### 기준선(최종 원복 상태)
- rollup:
  - `mape=0.010996830747457277`
  - `p95=0.025614369256908187`
  - `max=0.03537628179947446`
  - `pass=true`
- selected:
  - heavy2 fight2 `1D41=+1,027,920`
  - heavy2 fight2 `64AC=-13,398`
  - heavy4 fight5 DRG `64AC=+707,783`
  - lindwurm fight8 DRG surface `delta=20,776`

### 오늘 실험 결과(핵심만)
1. `status0_snapshot_redistribution defer` 계열
- 결론: no-op (실발동 0)

2. `single_source_binding` (tracked_target 경로)
- 결론: no-op
- 원인: 대상 케이스 대부분이 `accepted_by_source=true` 경로

3. `fallback split` 차단 계열
- `recent evidence missing` 축 차단:
  - heavy2 개선 큼 (`1D41 +699,098`, `64AC -171,834`)
  - 그러나 gate fail (`p95 0.03478+`) -> 기각/원복
- `rawSourceMatches=0 && recent evidence 없음 && activeTargets>=2 && tracked>=3`:
  - heavy2 개선 (`1D41 +792,119`, `64AC -138,618`)
  - gate fail (`p95 0.030681...`) -> 기각
- 동일 조건 `tracked>=4`로 축소:
  - gate pass
  - 하지만 `mape=0.011527994850365951`로 기준선 악화 -> 기각

4. source 성격 확인
- 문제 source `10128857`, `102BF7AE`는 heavy2 PartyList에 존재(실 party actor).
- 즉 "non-party source" 축 가설은 핵심 원인이 아님.

### 현재 해석
- heavy2 잔차의 큰 축은 여전히 `status0_fallback_tracked_target_split`의 근거 약한 분배.
- 다만 이 축을 전역적으로 막으면 gate/p95/mape가 쉽게 악화.
- 다음 단계는 "차단"보다 "근거 기반 재귀속(단일 귀속/보류)" 방향이 필요.

### 내일 시작 순서(고정)
1. `tasks.md`/`docs/parity-patch-notes.md` 확인
2. baseline 재확인:
   - `ActIngestionServiceTest`
   - `SubmissionParityRegressionGateTest`
   - selected/rollup diagnostics
3. fallback 경로에서 **차단 대신 재귀속** 가설 1개만 반영
4. 즉시 gate/selected 재검증

### 운영 메모(403)
- 403은 웹 세션 문제이며 로컬 gradle/diagnostics와 무관.
- 중단 시에도 로컬 결과는 `build/test-results/test/TEST-com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.xml`에서 복구 가능.

## 2026-04-21 추가 실험 13 (`64AC` recent-exact single-target에서 foreign `1D41` 감쇠, 채택)

### 현재 관찰
- heavy2 핵심 assignment 버킷:
  - `activeTargets=1|trackedTargets=4+|sourceTracked=1|sourceTrackedAction=64AC|recentSource=64AC|recentExact=64AC|foreignActionSet=1D41,4094,409C`
- 기존 "foreign `1D41` 완전 제외"는 selected 개선은 있었지만 rollup `mape` 악화로 기각된 이력이 있음.

### 가설
- 동일 버킷에서 foreign `1D41`를 완전 제거하지 않고 가중치만 감쇠(`0.5`)하면
  heavy2 `1D41/64AC` 동시 개선을 유지하면서 전역 부작용을 줄일 수 있다고 가정.

### 수정 범위
- 파일: `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- `tryEmitStatus0KnownSourceTrackedRouting`에 분기 추가:
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
  - mode: `status0_tracked_target_split_dampened_foreign_recent_exact_action`
- 적용 조건(엄격 제한):
  - `acceptedBySource && status=0`
  - `activeTargets==1`
  - `trackedDots>=4 && sourceTrackedDots==1`
  - `recentSource==recentExact==sourceTrackedAction==64AC`
  - `foreignSourceCount>=3 && foreignActionCount>=3`
  - foreign tracked 후보에 `1D41` 존재
- 동작:
  - foreign `1D41` 후보만 weight `0.5`, 나머지는 `1.0`으로 planner 재분배.

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41`: `+1,027,920 -> +1,003,624` (개선)
  - heavy2 fight2 `64AC`: `-13,398 -> -5,298` (개선)
  - heavy4 fight5 DRG `64AC`: `+707,783` (동일)
  - lindwurm fight8 DRG surface: `delta=20,776` (동일)
- rollup/gate:
  - `mape=0.010972024677770337` (기준선 `0.010996830747457277` 대비 개선)
  - `p95=0.025671398260816086` (기준선 대비 소폭 악화)
  - `max=0.03537628179947446` (동일)
  - `pass=true`

### 남은 리스크
- `p95`가 소폭 상승했으므로, 다음 턴에서는 신규 mode의 발동 분포(특히 heavy2 all-fights)를 확인해
  국소 개선이 다른 fight에 누적 악화를 만드는지 추가 점검 필요.

## 2026-04-21 추가 실험 14 (`foreign 1D41` 감쇠계수 `0.50 -> 0.65`, 기각/원복)

### 현재 관찰
- 실험 13(`0.50`) 적용 후 신규 mode 발동:
  - `status0_tracked_target_split_dampened_foreign_recent_exact_action=28` (heavy2 selected fight2)
- all-fights에서 fight2 p95가 상대적으로 높아(`0.031018`) 감쇠 강도를 약화해 p95를 더 줄일 수 있는지 확인 필요.

### 가설
- 감쇠계수를 `0.50 -> 0.65`로 완화하면
  heavy2 개선을 일부 유지하면서 `p95` 악화를 줄일 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `KNOWN_SOURCE_FOREIGN_HIGANBANA_WEIGHT_FACTOR: 0.50 -> 0.65`

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41`: `+1,003,624 -> +1,011,610` (악화)
  - heavy2 fight2 `64AC`: `-5,298 -> -7,960` (악화)
  - heavy4/lind 표면은 실질 동일
- rollup:
  - `mape=0.010974776698926972` (`0.50` 대비 악화, baseline 대비는 여전히 개선)
  - `p95=0.025652656136321898` (`0.50` 대비 소폭 개선)
  - `max=0.03537628179947446`
  - gate `pass=true`

### 결론/조치
- `p95` 소폭 개선 대가로 heavy2 핵심 residual + mape가 동시에 악화되어 기각.
- 감쇠계수 `0.50`으로 즉시 원복.
- 원복 후 최종 유지값(실험 13 상태) 재확인:
  - rollup: `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2: `1D41=+1,003,624`, `64AC=-5,298`
  - heavy4 fight5 DRG `64AC=+707,783`
  - lindwurm fight8 DRG surface `delta=20,776`

## 2026-04-21 추가 실험 15 (`foreign 1D41` 감쇠계수 `0.50 -> 0.40`, 기각/원복)

### 현재 관찰
- 실험 13(`0.50`) 상태에서 신규 mode 발동은 heavy2 selected fight2에 국한(`28 hits`)되어,
  추가 감쇠가 heavy2 residual을 더 낮출 수 있는지 검증 가치가 있다고 판단.

### 가설
- 감쇠계수를 `0.40`으로 더 낮추면 heavy2 `1D41/64AC`를 추가 개선할 수 있고,
  적용 범위가 좁아 전역 영향은 제한적일 것이라고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `KNOWN_SOURCE_FOREIGN_HIGANBANA_WEIGHT_FACTOR: 0.50 -> 0.40`

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41`: `+1,003,624 -> +997,903` (개선)
  - heavy2 fight2 `64AC`: `-5,298 -> -3,391` (개선)
- 전역/all-fights:
  - rollup `mape=0.010988320220743043` (`0.50` 대비 악화)
  - rollup `p95=0.025684824718896618` (`0.50` 대비 악화)
  - heavy2 fight2(all-fights) `mape=0.013705`, `p95=0.031050` (`0.50` 대비 악화)
  - gate `pass=true`

### 결론/조치
- selected 개선 대비 전역/일반화 지표 악화로 기각.
- 감쇠계수 `0.50`으로 즉시 원복.
- 원복 후 유지값 재확인:
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`
  - heavy2 fight2 `1D41=+1,003,624`, `64AC=-5,298`

## 2026-04-21 추가 실험 16 (dampened split action-set 제한, no-op/원복)

### 가설
- dampened split을 heavy2에서 관측된 foreign action-set(`1D41,4094,409C`)에만 제한하면
  p95 부작용을 줄일 수 있다고 가정.

### 수정 범위
- `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`에
  foreign action set exact-match 조건 추가.

### 검증 결과
- baseline/gate pass.
- selected/rollup/all-fights 수치 완전 동일:
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`
  - heavy2 fight2 `1D41=+1,003,624`, `64AC=-5,298`
  - mode count 동일(`status0_tracked_target_split_dampened_foreign_recent_exact_action=28`)

### 결론/조치
- 실효 발동 집합이 기존과 동일해 no-op.
- 복잡도 증가 방지를 위해 조건 추가는 즉시 원복.

## 2026-04-21 추가 실험 17 (dampened split을 foreign `1D41` snapshot 우세 구간으로 제한, 기각/원복)

### 가설
- `1D41`가 foreign snapshot weight에서 우세할 때만 감쇠를 적용하면
  불필요한 감쇠를 줄여 p95를 개선할 수 있다고 가정.

### 수정 범위
- `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`에
  `isForeignHiganbanaSnapshotDominant(...)` 조건 추가.
  - 기준: `foreignHiganbanaWeight / foreignTotalWeight >= 0.50`

### 검증 결과
- baseline/gate pass.
- mode coverage:
  - `status0_tracked_target_split_dampened_foreign_recent_exact_action: 28 -> 8`
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,022,371` (악화)
  - heavy2 fight2 `64AC: -5,298 -> -11,548` (악화)
- 전역:
  - rollup `mape=0.01098932611785624` (`0.50` 대비 악화)
  - rollup `p95=0.025627394399776036` (`0.50` 대비 개선)
  - heavy2 fight2(all-fights) `mape=0.013708` (`0.50` 대비 악화), `p95=0.030916` (`0.50` 대비 개선)
  - gate `pass=true`

### 결론/조치
- p95 개선 대가로 heavy2 핵심 residual + mape가 동시에 악화되어 기각.
- 조건 추가는 즉시 원복, `0.50` 기본 감쇠 로직 유지.

## 2026-04-21 추가 실험 18 (dampened split source-preferential reweight, 기각/원복)

### 가설
- 동일 coverage에서 감쇠로 줄인 몫을 source(64AC) 쪽으로 더 주면
  heavy2 개선을 유지하면서 p95를 안정화할 수 있다고 가정.

### 수정 범위
- `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`의 후보 가중치:
  - source 후보: `1.25`
  - foreign `1D41`: `0.50`
  - 그 외: `1.0`

### 검증 결과
- baseline/gate pass.
- selected:
  - heavy2 fight2 `1D41`: `+1,003,624 -> +1,001,463` (소폭 개선)
  - heavy2 fight2 `64AC`: `-5,298 -> +5,501` (절대오차 악화)
- 전역:
  - rollup `mape=0.010994894286671124` (`0.50` 대비 악화)
  - rollup `p95=0.02574742989207551` (`0.50` 대비 악화)
  - heavy2 all-fights fight2 `p95=0.031196` (`0.50` 대비 악화)
  - gate `pass=true`

### 결론/조치
- `64AC` 절대오차와 rollup/p95가 동시에 악화되어 기각.
- source-preferential reweight는 즉시 원복, `0.50` 기본 감쇠 로직 유지.

## 2026-04-21 추가 실험 19 (`foreign 1D41` 감쇠계수 `0.50 -> 0.58`, 기각/원복)

### 가설
- 감쇠를 완화(`0.58`)하면 p95를 줄이면서도 heavy2 개선을 유지할 수 있다고 가정.

### 수정 범위
- `KNOWN_SOURCE_FOREIGN_HIGANBANA_WEIGHT_FACTOR: 0.50 -> 0.58`

### 검증 결과
- baseline/gate pass.
- 전역/일반화:
  - rollup `mape=0.010969849168810239` (`0.50` 대비 개선)
  - rollup `p95=0.025661210486908077` (`0.50` 대비 개선)
  - heavy2 all-fights fight2: `mape=0.013650`, `p95=0.030995` (`0.50` 대비 개선)
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,007,965` (악화)
  - heavy2 fight2 `64AC: -5,298 -> -6,745` (절대오차 악화)
  - heavy4/lind surface는 실질 동일.

### 결론/조치
- heavy2 핵심 residual 2축 동시 악화로 기각.
- 감쇠계수 `0.50`으로 즉시 원복.

## 2026-04-21 추가 실험 20 (adaptive dampening by foreign `1D41` snapshot share, 기각/원복)

### 가설
- 고정 감쇠(0.50) 대신 foreign `1D41` share 기반 적응 감쇠를 쓰면
  `1D41` 비중이 낮은 케이스의 과감쇠를 줄여 p95를 개선할 수 있다고 가정.

### 수정 범위
- `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`에서
  foreign `1D41` weight를 share 기반 선형 보간으로 산출:
  - share <= 0.34: weight 1.00
  - share >= 0.67: weight 0.50
  - 구간 내 선형 보간

### 검증 결과
- baseline/gate pass.
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,024,567` (악화)
  - heavy2 fight2 `64AC: -5,298 -> -12,279` (악화)
- 전역:
  - rollup `mape=0.010992292750428373` (`0.50` 대비 악화)
  - rollup `p95=0.025622247708188797` (`0.50` 대비 개선)
  - heavy2 all-fights fight2 `mape=0.013717` (`0.50` 대비 악화), `p95=0.030904` (`0.50` 대비 개선)
  - gate `pass=true`

### 결론/조치
- p95 개선 대가로 heavy2 핵심 residual + mape가 동시에 악화되어 기각.
- adaptive dampening 로직은 즉시 원복, 고정 감쇠 `0.50` 유지.

## 2026-04-21 추가 실험 21 (dampened split foreign action cardinality를 `==3`으로 고정, 기각/원복)

### 가설
- dampened split 진입 조건을 `foreignActionCount>=3`에서 `foreignActionCount==3`으로 좁히면
  heavy2 triad(`1D41,4094,409C`)만 남기고 비정형 케이스를 제외해 품질이 좋아질 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
  - 조건 변경: `foreignActionCount < 3` -> `foreignActionCount != 3`

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41`: `+1,003,624 -> +1,027,920` (악화)
  - heavy2 fight2 `64AC`: `-5,298 -> -13,398` (악화)
  - heavy4 fight5 DRG `64AC` surface: `+707,783 -> -84,727` (악화)
  - lindwurm fight8 DRG surface: `delta=20,776` (동일)
- 전역:
  - rollup `mape=0.010996830747457277` (`0.50` 기준선 대비 악화)
  - rollup `p95=0.025614369256908187` (`0.50` 기준선 대비 개선)
  - rollup `max=0.03537628179947446` (동일)
  - gate `pass=true`

### 결론/조치
- p95 단일 개선 대비 heavy2 핵심 residual 2축과 heavy4 surface가 동시에 악화되어 기각.
- 조건 변경은 즉시 원복(`foreignActionCount>=3` 복귀), 고정 감쇠 `0.50` 로직 유지.

## 2026-04-21 추가 실험 22 (dampened split foreign source cardinality를 `==3`으로 고정, no-op/원복)

### 가설
- dampened split 진입 조건을 `foreignSourceCount>=3`에서 `foreignSourceCount==3`으로 좁히면
  heavy2의 tri-source 케이스에만 작동해 전역 부작용을 줄일 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
  - 조건 변경: `foreignSourceCount < 3` -> `foreignSourceCount != 3`

### 검증 결과
- baseline/gate pass.
- selected/전역/all-fights 수치가 유지값과 동일:
  - heavy2 fight2 `1D41=+1,003,624`, `64AC=-5,298`
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`
  - gate `pass=true`
- mode breakdown에서도 dampened 모드가 기존과 동일하게 관측되어 실질 발동 집합 변화가 확인되지 않음.

### 결론/조치
- 효과 없는 조건 강화(no-op)로 판단.
- 복잡도 증가 방지를 위해 즉시 원복(`foreignSourceCount>=3` 복귀), 고정 감쇠 `0.50` 로직 유지.

## 2026-04-21 추가 실험 23 (`foreign 1D41` 감쇠계수 `0.50 -> 0.45`, 기각/원복)

### 가설
- `0.40`이 selected 개선/전역 악화를 보였고 `0.50`이 전역 안정점이라면,
  중간값 `0.45`에서 heavy2 개선을 일부 유지하면서 전역 악화를 줄일 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `KNOWN_SOURCE_FOREIGN_HIGANBANA_WEIGHT_FACTOR: 0.50 -> 0.45`

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,000,804` (개선)
  - heavy2 fight2 `64AC: -5,298 -> -4,358` (개선)
- 전역/all-fights:
  - rollup `mape=0.010980057090142827` (`0.50` 대비 악화)
  - rollup `p95=0.025678016441516502` (`0.50` 대비 악화)
  - heavy2 all-fights fight2 `mape=0.013680`, `p95=0.031034` (`0.50` 대비 악화)
  - gate `pass=true`
- heavy4/lindwurm surface:
  - heavy4 fight5 DRG `delta=-84,727` (동일)
  - lindwurm fight8 DRG `delta=20,776` (동일)

### 결론/조치
- selected 개선 대비 rollup/all-fights 지표가 동시 악화되어 기각.
- 감쇠계수 `0.50`으로 즉시 원복.

## 2026-04-21 추가 실험 24 (dampened split에서 foreign non-`1D41` 약감쇠, 기각/원복)

### 가설
- 현재 dampened 모드는 heavy2 fight2에서만 발동하므로,
  foreign `1D41` 감쇠로 빠진 몫이 `4094/409C`로 과재분배되는 것을 줄이면
  heavy2 이득을 유지하면서 p95를 줄일 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
  - 후보 가중치 조정:
    - foreign `1D41`: `0.50` (유지)
    - foreign non-`1D41`(and non-source action): `0.90` 신규 약감쇠
    - 그 외: `1.0`

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,005,586` (악화)
  - heavy2 fight2 `64AC: -5,298 -> -1,370` (개선)
- 전역/all-fights:
  - rollup `mape=0.010974231797681417` (`0.50` 대비 악화)
  - rollup `p95=0.02569905380740251` (`0.50` 대비 악화)
  - heavy2 all-fights fight2 `mape=0.013663`, `p95=0.031083` (`0.50` 대비 악화)
  - gate `pass=true`
- heavy4/lindwurm surface:
  - heavy4 fight5 DRG `delta=-84,727` (동일)
  - lindwurm fight8 DRG `delta=20,776` (동일)

### 결론/조치
- `64AC` 단일 개선 대가로 `1D41` 및 rollup/all-fights 지표가 동시 악화되어 기각.
- foreign non-`1D41` 약감쇠 로직은 즉시 원복, 고정 감쇠 `0.50` 유지.

## 2026-04-22 추가 실험 25 (dampened 후보를 `source + foreign 1D41`로 제한, 기각/원복)

### 현재 관찰
- `status0_tracked_target_split_dampened_foreign_recent_exact_action`는 heavy2 fight2에서만 발동함을 확인.
- 기존 감쇠는 foreign `1D41`만 줄이고 나머지 후보는 유지하므로, 감쇠 손실분이 `4094/409C`로 재분배될 수 있음.

### 가설
- dampened 후보를 `source(64AC) + foreign 1D41`로만 제한하면
  `4094/409C` 교차 오염을 차단해 heavy2 핵심 residual과 전역 지표를 동시에 개선할 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
  - 후보 필터 추가:
    - `trackedDot.sourceId() == dot.sourceId()` 또는 `trackedDot.actionId() == 1D41`만 allocation 후보로 유지.

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,046,815` (악화)
  - heavy2 fight2 `64AC: -5,298 -> +81,091` (악화)
- 전역/all-fights:
  - rollup `mape=0.011637349635327691` (`0.50` 대비 크게 악화)
  - rollup `p95=0.026856584538904167` (`0.50` 대비 크게 악화)
  - heavy2 all-fights fight2 `mape=0.015652`, `p95=0.032438` (`0.50` 대비 크게 악화)
  - gate `pass=true`
- mode 신호:
  - dampened amount 급증
    - `1D41 amount=75,589`
    - `64AC amount=151,179`
  - 기존 대비 source 쏠림이 커지며 64AC 과대가 재발.

### 결론/조치
- heavy2 핵심 2축과 rollup/all-fights를 동시에 악화시키므로 기각.
- 후보 제한 필터는 즉시 원복, 고정 감쇠 `0.50` 로직 유지.

## 2026-04-22 추가 실험 26 (dampened 전용 recentExact window 축소 `15s -> 8s`, 기각/원복)

### 가설
- `dampened`는 recent-exact 근거에 의존하므로, 전용 exact window를 15초에서 8초로 줄이면
  오래된 근거로 인한 오분배를 줄여 all-fights p95를 개선할 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `KNOWN_SOURCE_DAMPENED_EXACT_WINDOW_MS = 8_000L` 추가
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`에서
    `resolveRecentExactUnknownStatusActionId(..., 8_000L)` 사용

### 검증 결과
- baseline:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- mode coverage:
  - `status0_tracked_target_split_dampened_foreign_recent_exact_action` hits가 축소
  - heavy2 `1D41 amount=16,531 hits=3`, `64AC amount=33,060 hits=3` (기존 hits=7 대비 감소)
- selected:
  - heavy2 fight2 `1D41: +1,003,624 -> +1,015,522` (악화)
  - heavy2 fight2 `64AC: -5,298 -> -9,265` (악화)
- 전역/all-fights:
  - rollup `mape=0.01098006722793318` (`0.50` 대비 악화)
  - rollup `p95=0.02564346813013674` (`0.50` 대비 개선)
  - heavy2 all-fights fight2 `mape=0.013680` (`0.50` 대비 악화), `p95=0.030953` (`0.50` 대비 개선)
  - gate `pass=true`
- heavy4/lindwurm surface:
  - heavy4 fight5 DRG `delta=-84,727` (동일)
  - lindwurm fight8 DRG `delta=20,776` (동일)

### 결론/조치
- p95 단일 개선 대비 heavy2 핵심 residual 2축과 rollup mape가 동시 악화되어 기각.
- dampened 전용 window 축소 변경은 즉시 원복, `15s` 기본 window 유지.

## 2026-04-22 종료 핸드오프 (내일 이어서)

### 오늘 종료 시점 고정 상태
- production 유지:
  - `KNOWN_SOURCE_FOREIGN_HIGANBANA_WEIGHT_FACTOR = 0.50`
  - dampened recentExact window = `15s` (실험 26 원복 완료)
- 최근 실험 상태:
  - 21~26 모두 `기각/원복` 또는 `no-op`
  - 공통 패턴: `p95` 개선 시 heavy2 핵심 residual(`1D41/64AC`) 또는 `mape` 악화

### 내일 시작 즉시 확인
1. `ActIngestionServiceTest`
2. `SubmissionParityRegressionGateTest`
3. selected 진단:
   - heavy2 fight2 `1D41/64AC` target parity + mode breakdown
   - heavy4/lindwurm surface
   - heavy2 all-fights fight2 quality line

### 내일 우선 가설 방향
- `weight` 스윕보다 **발동 전 evidence gate 1개**를 추가하는 방향 유지.
- 조건:
  - heavy2에만 explainable하게 작동해야 함
  - heavy4/lindwurm/gate 영향 최소여야 함
  - 변경은 1개만 반영 후 즉시 회귀 검증

### 운영 메모
- Gradle는 병렬 실행 시 `build/test-results` 또는 `build/classes` 경합이 재발할 수 있으므로
  중요한 검증은 단독 순차 실행 권장.

## 2026-04-22 추가 실험 27 (dampened foreign non-`1D41` no-evidence 약감쇠, no-op/원복)

### 현재 관찰
- `status0_tracked_target_split_dampened_foreign_recent_exact_action`은 heavy2 selected(fight2)에서만 발동.
- heavy2 핵심 잔차는
  - `1D41 delta=+1,003,624`
  - `64AC delta=-5,298`
  상태를 유지 중.

### 가설
- dampened 후보 중 foreign non-`1D41`(`4094/409C`)가 현재 target에 대한 source-evidence가 없을 때만
  약감쇠(`0.85`)하면 교차 오염을 줄일 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - dampened 후보 가중치에 `same-target source evidence` 조건부 약감쇠 추가.

### 검증 결과
- baseline/gate pass.
- selected/rollup/all-fights 수치 완전 동일(no-op):
  - heavy2 fight2 `1D41=+1,003,624`, `64AC=-5,298`
  - heavy2 all-fights fight2 `mape=0.013656`, `p95=0.031018`
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`
  - heavy4 fight5 DRG `64AC=+707,783`
  - lindwurm fight8 DRG surface `delta=20,776`

### 결론/조치
- dampened 발동 케이스에서 해당 evidence 조건이 이미 충족되어 실질 변화가 없다고 판단.
- 복잡도 증가 방지를 위해 즉시 원복.
- 최종 워크트리 clean 상태 유지.

### 운영 메모
- 이번 턴에서도 병렬 테스트 실행 시 `build/classes` 경합으로 `NoSuchFileException`/대량 compile 오류가 재현됨.
- 검증은 반드시 단독 순차 실행 유지.

## 2026-04-22 추가 실험 28 (dampened recentExact를 corroborated-only로 제한, no-op/원복)

### 현재 관찰
- `dampened` 진입 근거는 `recentExact`가 action/status 단독 근거로도 성립할 수 있어,
  근거 강도를 높이면 불필요 발동을 줄일 여지가 있다고 판단.

### 가설
- `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit`에서
  `recentExact`를 `action+status corroborated` 근거로만 허용하면
  heavy2 오염을 줄이면서 전역 영향은 제한될 수 있다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - dampened 진입 조건에 corroborated recentExact 일치 조건 1개 추가.

### 검증 결과
- baseline/gate pass.
- selected/전역/all-fights 수치 완전 동일(no-op):
  - heavy2 fight2 `1D41=+1,003,624`, `64AC=-5,298`
  - heavy2 all-fights fight2 `mape=0.013656`, `p95=0.031018`
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`
  - heavy4 fight5 DRG `64AC=+707,783`
  - lindwurm fight8 DRG surface `delta=20,776`

### 결론/조치
- 실효 발동 집합이 기존과 동일해 no-op로 판단.
- 복잡도 증가 방지를 위해 즉시 원복.

### 운영 메모
- 병렬 테스트 재시도에서 `build/classes` 경합으로 동일 compile 오류 재발.
- 이후 검증은 단독 순차 실행만 유지.

## 2026-04-22 추가 실험 29 (dampened activeTargets 허용 범위 `==1 -> <=2`, 기각/원복)

### 현재 관찰
- heavy2 `knownSourceTrackedTargetSplitProbe`에서
  `reason=recent_exact|activeTargets=2|sourceTracked=1` 버킷 비중이 큼.
- heavy4/lindwurm는 주로 `ineligible|activeTargets=1|sourceTracked=0` 버킷이 지배적.

### 가설
- dampened 분기 조건의 `activeTargets==1`을 `activeTargets<=2`로 완화하면
  heavy2의 `recent_exact + dual-target` 구간을 추가 흡수해
  `1D41/64AC` 동시 개선이 가능하다고 가정.

### 수정 범위
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
  - `resolveKnownSourceSingleTargetRecentExactForeignHiganbanaDampenedSplit(...)`
  - active target 조건: `==1` -> `1..2`

### 검증 결과
- baseline/gate pass.
- selected(heavy2 fight2):
  - `1D41: +1,003,624 -> +996,914` (개선)
  - `64AC: -5,298 -> -3,061` (개선)
- 하지만 전역/all-fights 악화:
  - rollup `mape=0.010991134103993142` (악화)
  - rollup `p95=0.02568714812275954` (악화)
  - heavy2 all-fights fight2 `mape=0.013714`, `p95=0.031055` (악화)
  - gate `pass=true`
- mode coverage 변화:
  - dampened가 prison 외 `레드 핫/딥 블루`에도 추가 발동 (각 1 hit씩 증가)

### 결론/조치
- selected 개선 대비 전역/일반화 지표가 동시 악화되어 기각.
- 조건 변경 즉시 원복 완료.
- 원복 후 기준선 복귀 확인:
  - heavy2 fight2 `1D41=+1,003,624`, `64AC=-5,298`
  - heavy2 all-fights fight2 `mape=0.013656`, `p95=0.031018`
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`

### 운영 메모
- 다중 테스트를 한 번에 묶어 실행할 때 `.gradle-home` 캐시 JAR 접근(`AccessDeniedException`)이 간헐 재발.
- 검증은 baseline/gate/diagnostics를 분리한 순차 실행으로 고정.

## 2026-04-22 추가 실험 30 (dual-target stale-evidence 한정 dampened 허용, no-op/원복)

### 가설
- `activeTargets=2`를 전면 허용하지 않고,
  `same source/action`이 **다른 target에는 최근 존재**하지만 **현재 target에는 최근 근거가 없는**
  stale dual-target 케이스로만 dampened를 확장하면 heavy2 오염을 줄일 수 있다고 가정.

### 수정 범위
- `ActIngestionService`
  - dampened 조건을 `activeTargets<=2`로 확장
  - 단, `activeTargets==2`일 때 stale dual-target evidence helper 조건 추가.

### 검증 결과
- baseline/gate pass.
- selected/all-fights/rollup 수치 및 mode coverage가 baseline과 완전 동일(no-op).

### 결론/조치
- 실효 발동 케이스가 없어서 no-op로 판단.
- 복잡도 증가 방지를 위해 즉시 원복.

## 2026-04-22 추가 실험 31 (`recentSource only + dual-target` 전용 foreign 1D41 약감쇠, no-op/원복)

### 현재 관찰
- heavy2 probe에서 `recent_exact`/`stale_evidence`의 `activeTargets=2, sourceTracked=1` 버킷 비중이 큼.

### 가설
- `recentSource=64AC`, `recentExact=null`, `activeTargets=2` 케이스만 별도 분기로 분리해
  foreign `1D41`을 보수 약감쇠(`0.80`)하면 heavy2 누수를 줄일 수 있다고 가정.

### 수정 범위
- `ActIngestionService`
  - 신규 mode:
    - `status0_tracked_target_split_dampened_foreign_recent_source_only_dual_target`
  - 조건:
    - `acceptedBySource && status=0`
    - `activeTargets==2`, `trackedDots>=4`, `sourceTracked=1`
    - `recentSource=64AC`, `recentExact=null`, foreign source/action cardinality `>=3`
    - foreign `1D41` 존재

### 검증 결과
- baseline/gate pass.
- 신규 mode hit 미발생(0 hit).
- selected/all-fights/rollup 수치 완전 동일(no-op).

### 결론/조치
- 실효 커버리지 부재로 no-op 판단.
- 신규 분기/상수 즉시 원복.

## 2026-04-23 이어하기 핸드오프

### 오늘 종료 시점 고정 상태
- production 코드 변경 없음(실험 28~31 모두 기각/원복).
- 기준선:
  - heavy2 fight2 `1D41=+1,003,624`
  - heavy2 fight2 `64AC=-5,298`
  - heavy2 all-fights fight2 `mape=0.013656`, `p95=0.031018`
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`
- baseline 테스트:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass

### 내일 시작 즉시 실행
1. `ActIngestionServiceTest`
2. `SubmissionParityRegressionGateTest`
3. selected diagnostics:
   - heavy2 fight2 `1D41/64AC` target parity + mode breakdown
   - heavy4/lindwurm surface
   - heavy2 all-fights fight2 quality line

### 내일 가설 우선순위
- `dampened` 확장/가중치 튜닝은 우선순위 하향(반복 no-op 또는 전역 악화 패턴).
- 다음은 `status0_tracked_target_split`의 `stale_evidence + activeTargets=2 + sourceTracked=1` 버킷에 대해,
  suppress가 아닌 **분배 경로 선택** 가설 1개만 적용.
- 조건:
  - heavy2 이득을 설명 가능해야 함
  - heavy4/lindwurm/gate 영향 최소여야 함
  - 변경 1개 적용 후 즉시 회귀 검증

### 운영 메모
- 병렬 실행 금지( `build/classes` 경합 재발 ).
- `.gradle-home` 접근 오류(`AccessDeniedException`)가 간헐 발생하므로 테스트는 묶지 말고 단독 순차 실행.

## 2026-04-28 체크포인트 (`64AC recent-exact same-source dampening` 유지)

### 현재 관찰
- baseline:
  - heavy2 fight2 `1D41=+1,003,624`
  - heavy2 fight2 `64AC=-5,298` (target parity surface)
  - heavy2 all-fights fight2 `mape=0.013656`, `p95=0.031018`
  - rollup `mape=0.010972024677770337`, `p95=0.025671398260816086`, `max=0.03537628179947446`, `pass=true`
- actor rDPS surface에서는 heavy2 DRG `64AC` actor skill total이 여전히 과집계 축으로 남아 있음.

### 가설
- known-source `status=0` tick에서 `recentSource == recentExact == sourceTrackedAction == 64AC`이고
  같은 target에 foreign DoT action evidence가 2종 이상 공존하면, 같은 소스 DRG `64AC` 몫을 동등분배보다 낮추는 편이
  shared GUID/overaggregation을 줄인다.
- 전역 suppress가 아니라 accepted tick 내부의 weight 조정이므로, heavy4/lindwurm/all-fights gate를 덜 흔든다.

### 수정 범위
- `ActIngestionService`
  - 신규 경로:
    - `status0_tracked_target_split_recent_exact_chaotic_same_source_dampened`
  - 조건:
    - `acceptedBySource && status=0`
    - source가 known party member
    - `trackedDots.size() >= 3`
    - `sourceTrackedDots.size() == 1`
    - `recentSourceActionId == recentExactActionId == sourceTrackedActionId == 64AC`
    - foreign distinct action count `>= 2`
  - weight:
    - same-source `64AC = 0.125`
    - foreign candidates `= 1.0`

### 검증 결과
- `ActIngestionServiceTest` pass (단독 실행)
- `SubmissionParityRegressionGateTest` pass (단독 실행)
- rollup:
  - `mape=0.009947746177787686`
  - `p95=0.021784436570755752`
  - `max=0.03537628179947446`
  - `pass=true`
- heavy2 all-fights fight2:
  - `mape=0.013349355288288303`
  - `p95=0.030010375805500228`
- heavy4 selected:
  - fight5 DRG `64AC` target parity `delta=+439,231`
- lindwurm selected submission:
  - `mape=0.0052672493669323394`
  - `p95=0.012846064736486258`
  - `max=0.016039626506684106`

### 남은 리스크
- heavy2 fight2 selected target parity surface는 악화:
  - `1D41=+1,010,199`
  - `64AC=-68,912`
- 하지만 actor rDPS/gate 우선 기준에서는 개선폭이 더 크므로 이번 변경은 유지.
- 추가 확인:
  - tracked=2까지 조건을 넓히는 확장은 수치 변화가 없어 no-op로 판단하고 원복.
  - same evidence 분기 안에서 same-source weight를 `0.50 -> 0.25 -> 0.00`으로 스윕.
  - `0.00`은 gate 통과 및 p95 개선이 있으나 rollup `mape`가 `0.25` 대비 악화.
  - `0.125`는 테스트한 값 중 rollup `mape`가 가장 낮고, `0.25` 대비 p95/heavy2 all-fights/heavy4/lindwurm도 개선되어 유지.
- diagnostics와 unit test를 같은 Gradle invocation에 섞으면 `ActIngestionServiceTest`가 Preferences/global-state 영향으로 실패할 수 있음.
  - baseline/gate는 단독 순차 실행으로 확인할 것.

## 2026-04-29 내일 이어하기 핸드오프

### 현재 작업 상태
- production 변경 있음:
  - `ActIngestionService`에 `status0_tracked_target_split_recent_exact_chaotic_same_source_dampened` 경로 추가
  - same-source `64AC` weight는 `0.125`
- 문서 변경 있음:
  - 이 체크포인트와 2026-04-28 `0.125` 결과가 `tasks.md`에 기록됨
- 아직 commit은 하지 않음.

### 현재 기준선
- `ActIngestionServiceTest`: pass
- `SubmissionParityRegressionGateTest`: pass
- rollup:
  - `mape=0.009947746177787686`
  - `p95=0.021784436570755752`
  - `max=0.03537628179947446`
  - `pass=true`
- heavy2 all-fights fight2:
  - `mape=0.013349355288288303`
  - `p95=0.030010375805500228`
- selected surface:
  - heavy2 fight2 DRG `64AC=-68,912`
  - heavy2 fight2 SAM `1D41=+1,010,199`
  - heavy4 fight5 DRG `64AC=+439,231`
  - lindwurm selected `mape=0.0052672493669323394`, `p95=0.012846064736486258`

### 해석
- 이번 변경은 selected heavy2 target parity를 더 나쁘게 만들지만, live rDPS 우선 지표인 rollup/heavy2 all-fights/heavy4/lindwurm를 동시에 개선한다.
- 따라서 현재는 유지가 합리적이다.
- 다음 작업은 selected target surface를 억지로 되돌리는 튜닝이 아니라, `64AC` actor over를 더 줄이면서 all-fights/gate를 유지하는 evidence 기반 분해여야 한다.

### 내일 시작 순서
1. `git status --short`로 변경 파일 확인
2. `tasks.md`와 `docs/parity-patch-notes.md` 확인
3. 테스트는 단독 순차 실행:
   - `ActIngestionServiceTest`
   - `SubmissionParityRegressionGateTest`
4. diagnostics:
   - heavy2 fight2 DRG `64AC` mode breakdown
   - heavy2 fight2 DRG same-source/foreign tracked split mix
   - heavy4 fight5 DRG `64AC` target parity
   - lindwurm fight8 DRG `64AC` same-source mix
   - rollup
5. production 가설은 1개만 적용하고 즉시 gate/rollup 재검증

### 다음 가설 후보
- 후보 1:
  - `recentExact=64AC` dampened 분기의 weight 추가 스윕은 잠정 중단.
  - `0.00`은 p95는 좋지만 rollup `mape`가 악화되어 유지하지 않음.
- 후보 2:
  - 남은 큰 덩어리인 기존 `status0_tracked_target_split`/foreign-only 경로를 분해.
  - 단, `recentExact=null` activeTargets=1 구간은 heavy4/lindwurm에도 큰 덩어리라 전역 변경 위험이 큼.
- 후보 3:
  - heavy2에서만 과집계로 보이는 `64AC` actor-skill total과 FFLogs event/window surface를 먼저 다시 비교.
  - production 변경 전에는 왜 heavy4/lindwurm를 덜 건드리는지 설명 가능한 evidence 조건을 만들어야 함.

### 주의
- diagnostics와 unit test를 같은 Gradle invocation에 섞지 말 것.
- selected fight target parity 개선만 보고 변경을 채택하지 말 것.
- `status=0` fallback 순서를 감으로 뒤집지 말 것.

## 2026-04-29 추가 실험 1 (`recentSource-only 64AC same-source dampening`, 기각/원복)

### 관찰
- 유지 중인 `recentExact=64AC` dampened 경로는 rollup/gate 기준으로 유효하지만, 남은 DRG `64AC` 과집계가 있음.
- selected fights assignment bucket:
  - heavy4 fight5: `activeTargets=1`, `sourceTrackedAction=64AC`, `recentSource=64AC`, `recentExact=na`, `foreignActions>=2`가 큰 덩어리
  - lindwurm fight8: 같은 형태의 `64AC` bucket 존재
  - heavy2 fight2의 유사 `64AC` bucket은 주로 `activeTargets=2`, `foreignActions=1`이라 구분 가능

### 가설
- `recentExact`는 없지만 `recentSource=sourceTrackedAction=64AC`이고 단일 active target에서 foreign DoT action이 2종 이상 공존하면,
  same-source `64AC` weight를 `0.125`로 낮춰 heavy4/lindwurm DRG `64AC` over를 줄일 수 있다고 가정.

### 수정 범위
- `ActIngestionService`
  - 임시 신규 경로:
    - `status0_tracked_target_split_recent_source_only_chaotic_same_source_dampened`
  - 조건:
    - `acceptedBySource && status=0`
    - source가 known party member
    - `activeTargets == 1`
    - `trackedDots.size() >= 3`
    - `sourceTrackedDots.size() == 1`
    - `recentSourceActionId == sourceTrackedActionId == 64AC`
    - `recentExactActionId == null`
    - foreign distinct action count `>= 2`
  - weight:
    - same-source `64AC = 0.125`
    - foreign candidates `= 1.0`

### 검증 결과
- baseline/gate:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
- rollup은 gate 유지지만 악화:
  - `mape=0.010261789553010697` (기준 `0.009947746177787686` 대비 악화)
  - `p95=0.021984607680971886` (기준 `0.021784436570755752` 대비 악화)
  - `max=0.03537628179947446`
  - `pass=true`
- 세부 영향:
  - heavy4 DRG `64AC` actor skill delta는 개선
    - `+354,504 -> +204,624`
  - 하지만 heavy4 Sage/Scholar 및 lindwurm AST 등 foreign DoT 쪽으로 손실분이 이동하며 전역 `mape/p95`가 악화
  - heavy2 selected도 소폭 악화
    - heavy2 all-fights fight2 `mape=0.013349355288288303 -> 0.013367421587771808`
    - `p95=0.030010375805500228 -> 0.029924292518942974`로 p95만 소폭 개선

### 결론/조치
- DRG `64AC` 단일 축은 개선됐지만 attribution 이동으로 전역 품질이 악화되어 기각.
- production 코드 즉시 원복.
- 원복 후 재확인:
  - `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - rollup `mape=0.009947746177787686`, `p95=0.021784436570755752`, `max=0.03537628179947446`, `pass=true`

### 다음 우선순위
- `recentSource-only` 단일 active target 구간은 전역 dampening 대상이 아님.
- 다음 가설은 same-source를 줄이는 방식보다, foreign recipient 중 이미 과대인 healer DoT로 재분배되는 부작용을 막는 evidence 조건을 먼저 찾아야 함.

## 2026-04-29 추가 실험 2 (`snapshot same-source 64AC active-subset 우회`, no-op/원복)

### 관찰
- heavy4 fight5 DRG `64AC` mode breakdown에서 남은 큰 축은:
  - `status0_snapshot_redistribution`: `843,748`
  - `status0_tracked_target_split`: `408,226`
  - 채택된 `recent_exact_chaotic_same_source_dampened`: `56,618`
- 따라서 채택된 dampened 경로를 더 줄이는 것보다 snapshot 경로를 분해하는 편이 남은 `64AC` actor over에 더 직접적임.
- source-class 진단:
  - heavy4 fight5 snapshot:
    - `party|path=active_subset hits=103 damage=3,493,247`
    - `party|path=same_source hits=65 damage=2,186,328`
    - `unknown|path=active_subset hits=6 damage=162,485`
  - lindwurm fight8 snapshot:
    - `party|path=same_source hits=77 damage=2,360,352`
    - `party|path=active_subset hits=39 damage=976,659`
    - `unknown|path=active_subset hits=6 damage=310,045`

### 가설
- status=0 snapshot에서 party raw source라는 이유만으로 same-source weights를 우선 선택하는 것이 DRG `64AC` over의 일부일 수 있다고 가정.
- 단, 전역적으로 뒤집지 않고 `sameSourceWeights`에 `64AC`가 있고 active foreign action이 2종 이상인 경우만 active subset으로 우회해 본다.

### 수정 범위
- `ActIngestionService`
  - `selectSnapshotRedistributionWeights(...)`에서 same-source 반환 전에 active subset을 계산
  - 임시 helper:
    - `shouldBypassKnownSourceSnapshotSameSourceWeights(...)`
  - 1차 조건: `recentSource=64AC`까지 요구
  - 2차 조건: `recentSource` 요구 제거, `sameSourceWeights`에 `64AC` + foreign actions `>=2`

### 검증 결과
- 1차/2차 모두 baseline/gate pass.
- rollup 수치가 기준선과 완전히 동일:
  - `mape=0.009947746177787686`
  - `p95=0.021784436570755752`
  - `max=0.03537628179947446`
  - `pass=true`
- actor surface도 동일:
  - heavy4 DRG `64AC` actor skill delta `+354,504`
  - heavy2 DRG `64AC` actor skill delta `+79,215`
  - lindwurm DRG `64AC` actor skill delta `+141,527`

### 결론/조치
- 진단의 `party|same_source` 큰 버킷이 이번 우회 조건과 실효로 맞물리지 않아 no-op로 판단.
- 복잡도 증가 방지를 위해 production 코드 원복.
- 임시 heavy4 source-class diagnostic test도 제거.

### 다음 우선순위
- snapshot 경로를 더 건드리려면 먼저 `status0_snapshot_redistribution`의 실제 emitted recipient/action별 source-class를 계측해야 함.
- 현재 source-class bucket만으로는 DRG `64AC` over에 직접 걸리는 조건을 만들기 부족함.

## 2026-04-29 추가 진단 3 (`snapshot recipient/action emission 계측`)

### 관찰
- `status0_snapshot_redistribution`의 source-class/path bucket만으로는 실제 어느 action/source에 damage가 emitted 되는지 알 수 없어,
  다음 production 가설을 만들기 전에 recipient/action 표면을 계측했다.
- 새 진단은 attribution 상태를 바꾸지 않도록 recent-evidence resolver를 호출하지 않고,
  `path`, raw source class, same/foreign relation, active target count, assigned action/source만 기록한다.

### 진단 결과
- heavy2 fight2:
  - totals: `hits=51`, `amount=446,807`
  - top buckets:
    - `active_subset|party|foreign|activeTargets=1|1D41`: `147,432`
    - `active_subset|party|foreign|activeTargets=1|409C`: `113,186`
    - `active_subset|party|foreign|activeTargets=1|64AC`: `94,780`
    - `active_subset|party|foreign|activeTargets=1|4094`: `89,921`
- heavy4 fight5:
  - totals: `hits=290`, `amount=3,385,838`
  - top buckets:
    - `active_subset|party|foreign|activeTargets=1|5EFA`: `1,170,994`
    - `active_subset|party|foreign|activeTargets=1|409C`: `1,094,803`
    - `active_subset|party|foreign|activeTargets=1|64AC`: `789,520`
    - `active_subset|party|foreign|activeTargets=1|9094`: `168,036`
- lindwurm fight8:
  - totals: `hits=118`, `amount=1,180,127`
  - top buckets:
    - `active_subset|party|foreign|activeTargets=1|40AA`: `308,543`
    - `active_subset|party|foreign|activeTargets=1|409C`: `282,225`
    - `active_subset|party|foreign|activeTargets=1|64AC`: `256,700`
    - `active_subset|unknown|foreign|activeTargets=1|409C`: `140,048`

### 해석
- selected fights의 snapshot 큰 덩어리는 모두 `active_subset + foreign + activeTargets=1`로 emitted 된다.
- 이전 `party raw source라 same-source snapshot 우선이 64AC over 원인일 수 있다`는 가설은 실제 emission 표면과 맞지 않는다.
- heavy4/lindwurm에서도 `64AC`는 이미 foreign recipient로 큰 비중을 받으므로,
  snapshot을 전역 dampening하면 DRG만 줄이는 것이 아니라 SGE/SCH/AST 등 다른 foreign DoT attribution을 같이 흔들 가능성이 크다.

### 검증 결과
- `ActIngestionServiceTest` pass
- `SubmissionParityRegressionGateTest` pass
- `debugStatus0SnapshotRedistributionRecipientBuckets_forSelectedFights_printsTopRecipients` pass
- rollup 변화 없음:
  - `mape=0.009947746177787686`
  - `p95=0.021784436570755752`
  - `max=0.03537628179947446`
  - `pass=true`

### 다음 우선순위
- 당장 production rule을 추가하지 않는다.
- 다음 가설은 snapshot의 `active_subset + foreign + activeTargets=1` 내부에서,
  특정 action을 줄이는 규칙이 아니라 recipient 후보군 중 이미 over/under가 어떻게 섞이는지 먼저 비교해야 한다.
- 특히 heavy4/lindwurm의 `5EFA/409C/40AA/64AC`가 함께 움직이므로,
  `64AC`만 줄이는 방식은 all-fights gate를 깨거나 다른 healer/caster DoT over를 만들 위험이 크다.

## 2026-04-29 추가 진단/실험 4 (`snapshot active_subset foreign over/under 분해`, suppress 기각)

### 관찰
- `status0_snapshot_redistribution`의 top `active_subset + foreign + activeTargets=1` recipient에 대해
  local skill total, FFLogs ability total, skill delta, actor rDPS delta를 함께 출력하는 진단을 추가했다.
- 계측 키에는 `rawSource` id도 추가했다.

### 진단 결과
- heavy2 fight2:
  - rawSource `10128857`가 대부분.
  - `1D41`: snapshot `139,533`, skillDelta `+21,271`
  - `64AC`: snapshot `88,568`, skillDelta `+79,215`
  - `409C`: snapshot `106,142`, skillDelta `-124,256`
  - `4094`: snapshot `83,457`, skillDelta `-271,294`
- heavy4 fight5:
  - rawSource `10128857`가 대부분.
  - `5EFA`: snapshot `990,176`, skillDelta `+152,412`
  - `409C`: snapshot `933,690`, skillDelta `+47,766`
  - `64AC`: snapshot `654,353`, skillDelta `+354,504`
  - `9094`: snapshot `164,347`, skillDelta `-216,108`
- lindwurm fight8:
  - rawSource `101E86E4`가 큰 축.
  - `40AA`: snapshot `208,124`, skillDelta `+96,174`
  - `409C`: snapshot `198,234`, skillDelta `+23,163`
  - `64AC`: snapshot `164,339`, skillDelta `+141,527`
  - `9094`: snapshot `29,086 + 22,614`, skillDelta `-196,243`

### 가설
- party raw source인데 same-source 후보가 없고, foreign active subset 후보가 3개 이상인 snapshot tick은
  raw source와 직접 연결되지 않은 speculative attribution이므로 suppress하면 heavy4/lindwurm의 over를 줄일 수 있다고 가정.

### 수정 범위
- 임시 production 변경:
  - `selectSnapshotRedistributionWeights(...)`
  - 조건:
    - raw source가 known party member
    - activeWeights size `>=3`
    - activeWeights에 raw source 후보 없음
  - 결과:
    - `suppressed_party_foreign_active_subset`으로 empty weights 반환

### 검증 결과
- `ActIngestionServiceTest`: pass
- `SubmissionParityRegressionGateTest`: fail
  - rollup max actor APE `0.06657452677209132`로 gate 실패
  - heavy2 all-fights fight8 p95 `0.07191636895033413`로 gate 실패
- 조치:
  - production suppress 즉시 원복
  - 원복 후:
    - `ActIngestionServiceTest` pass
    - `SubmissionParityRegressionGateTest` pass
    - rollup `mape=0.009947746177787686`, `p95=0.021784436570755752`, `max=0.03537628179947446`, `pass=true`

### 결론
- `active_subset + foreign + activeTargets=1` 전체 suppress는 과도하다.
- heavy4/lindwurm의 over bucket은 줄일 수 있어 보이지만, heavy2 all-fights에서 크게 깨진다.
- 이 경로는 under recipient(`9094`, heavy2 healer DoT)와 over recipient가 섞여 있어 전역 drop/dampen 후보가 아니다.

### 다음 우선순위
- snapshot active subset을 전역 suppress하지 않는다.
- 다음 가설은 rawSource 단위 suppress가 아니라, candidate set 안의 over/under 혼합을 설명할 수 있는 live evidence를 먼저 찾아야 한다.
- 특히 `rawSource=10128857`처럼 여러 fights에서 반복되는 party source가 왜 foreign DoT active subset으로 들어가는지,
  해당 raw source의 status payload/action evidence와 active target lifecycle을 추가 분해해야 한다.

## 2026-04-30 추가 진단/실험 5 (`rawSource status evidence`, PLD `0xF8 -> 0x17` 기각)

### 관찰
- `rawSource=10128857` 타임라인 진단을 추가했다.
- heavy2/heavy4에서 `status=0 DoT` tick 직전에 같은 raw source가 target에 `status=0xF8`를 적용하는 장면이 확인됐다.
  - 예: heavy2 fight2 `재의(10128857)`가 `레드 핫`에 `파멸의 진(0xF8)` 적용 후 같은 sourceName으로 `status=0` DoT tick 발생.
- 따라서 이 일부 snapshot 오염은 단순한 party raw source 잡음이 아니라,
  PLD Circle of Scorn 계열 status/action 매핑 누락으로 raw-source DoT가 active foreign subset으로 밀리는 현상으로 보인다.

### 가설
- PLD `status 0xF8 -> action 0x17`을 catalog에 복원하면,
  raw-source PLD DoT가 foreign active subset으로 분배되지 않아 `64AC/5EFA/409C` over가 줄어들 수 있다고 가정.

### 수정 범위
- 임시 변경:
  - `dot-attribution-catalog.json`의 job `19`에 `0xF8 -> 0x17` 추가
  - `DotAttributionCatalog.INVALID_DOT_ACTION_IDS`에서 `0x17` 제외
  - catalog test 갱신

### 검증 결과
- `DotAttributionCatalogTest`: pass
- `ActIngestionServiceTest`: pass
- `SubmissionParityRegressionGateTest`: pass
- 그러나 rollup diagnostic gate는 실패:
  - `mape=0.012261307980852718` (기준 `0.009947746177787686` 대비 악화)
  - `p95=0.034458393187682054` (gate 기준 `0.03` 초과)
  - `max=0.03812875792913093`
  - `pass=false`
- 세부 영향:
  - heavy2 DRG `64AC`는 개선: `+79,215 -> -24,801`
  - heavy4 DRG `64AC`도 개선: `+354,504 -> +255,637`
  - 하지만 heavy2 WHM/SCH/SAM DoT가 크게 under로 이동:
    - WHM `4094`: `-271,294 -> -603,662`
    - SCH `409C`: `-124,256 -> -390,536`
    - SAM `1D41`: `+21,271 -> -250,148`

### 결론/조치
- PLD raw-source evidence 자체는 실제로 존재하지만, `0xF8 -> 0x17` 전역 복원은 FFLogs live rDPS parity 기준에서 과도하다.
- selected DRG `64AC`만 보고 채택하면 heavy2 all-fights/rollup을 깨는 변경이다.
- production 변경은 즉시 원복.
- 원복 후 재검증:
  - `DotAttributionCatalogTest` + `ActIngestionServiceTest` pass
  - `SubmissionParityRegressionGateTest` pass
  - rollup `mape=0.009947746177787686`, `p95=0.021784436570755752`, `max=0.03537628179947446`, `pass=true`

### 다음 우선순위
- PLD `0xF8 -> 0x17` 같은 전역 catalog 복원은 현재 채택하지 않는다.
- 다음은 raw-source status evidence가 있어도 FFLogs companion live surface가 이를 어떻게 다르게 취급하는지,
  target lifecycle 또는 status snapshot candidate set의 over/under 보정 없이 설명 가능한 조건을 더 찾아야 한다.

### 다음 턴 시작점
1. `tasks.md` / `docs/parity-patch-notes.md` 확인 후 baseline 재확인.
2. `debugStatus0RawSourceTimeline_forSnapshotHotSources_printsNearbyEvidence`로 `rawSource=10128857`, `rawSource=101E86E4` 주변 evidence를 다시 본다.
3. production 변경 전에는 `0xF8 -> 0x17` 전역 복원은 기각 상태임을 전제로 둔다.
4. 다음 가설은 catalog 복원이 아니라, raw-source DoT evidence가 있어도 active foreign candidate set으로 남겨야 하는 조건을 찾아야 한다.
5. 변경을 한다면 1개만 적용하고 즉시 `ActIngestionServiceTest`, `SubmissionParityRegressionGateTest`, rollup을 순차 확인한다.
