# Parity Patch Notes

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
