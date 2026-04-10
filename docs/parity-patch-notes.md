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

## 다음 액션
1. 현재 고정 상수(`same-source=0.00`, `foreign-dominant=0.00`)에서 회귀 안정성 유지 확인
2. 새 suppression 조건의 `mape` 악화 actor를 먼저 식별
3. 필요 시 threshold(`foreignSourceCount`) 1단계 미세조정
4. 변경은 항상 1개씩, baseline/gate 즉시 재검증
