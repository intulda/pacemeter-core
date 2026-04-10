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
