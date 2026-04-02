# paceMeter 작업 메모

## 2026-04-03 현재 기준

### 최우선 목표
- `live rDPS parity`
- 기준:
  - `pacemeter live rDPS ~= FFLogs companion live rDPS`
  - replay parity는 검증 수단이다.
  - selected fight 하나만 맞추는 튜닝은 금지한다.
  - heavy2 / heavy4 / lindwurm / all-fights gate를 함께 본다.

## 현재 살아 있는 production 변경

### 1. unknown source + multi-target generic fallback 차단
- 파일:
  - `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- 내용:
  - `sourceId == E0000000`
  - `countTrackedTargetsWithActiveDots() > 1`
  - 이 경우 `status0_snapshot_redistribution`, `status0_tracked_target_split`의 generic fallback을 막는다.
- 이유:
  - heavy2의 `레드 핫 / 딥 블루 / 수중 감옥` 같은 multi-target 구조에서 unknown `status=0` tick이 mixed active set으로 과귀속되는 문제가 반복됐다.
  - lindwurm는 single-target 본체 케이스가 많아서 전역 차단이 아니라 multi-target 조건이 필요했다.

### 2. corroborated known-source 복원 경로 추가
- 파일:
  - `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- 현재 추가된 메서드:
  - `shouldPreferCorroboratedKnownSourceAttribution(...)`
- 현재 동작:
  - 아래 조건을 모두 만족할 때만 generic split보다 먼저 직접 복원한다.
  - `status=0`
  - source가 known party member
  - multi-target
  - same-source tracked dot 없음
  - target snapshot fallback set에 same-source key 없음
  - `application + status` evidence가 둘 다 맞는 corroborated action이 있음
- attribution mode:
  - `status0_corroborated_known_source`
- 의도:
  - `accepted_by_source`처럼 느슨하게 가지 않고, `same source + same target + corroborated action`이 있는 경우만 좁게 복원한다.

## 현재 검증 상태
- 통과:
  - `compileJava`
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`
- 주의:
  - Windows/Gradle 캐시 jar 잠금 때문에 diagnostics 단건 실행이 간헐적으로 실패했다.
  - 실패 원인은 로직보다 `AccessDeniedException` / `NoSuchFileException` 계열의 Gradle 캐시 충돌이었다.
  - `compileJava`와 regression gate는 현재 통과 상태로 확인됐다.

## 현재 확인된 수치

### baseline으로 잡고 있는 값
- rollup:
  - `mape=0.0129992412`
  - `p95=0.0339932391`
  - `max=0.0415261898`

### heavy2 fight2 / Samurai / 1D41
- diagnostics 결과:
  - `emittedTotal=2022286`
  - `raw21Total=276944`
  - `inferredDotTotal=1745342`
  - `fflogsAbilityTotal=1542816`
  - `delta=+479470`
- 해석:
  - 예전 `+576162`, 더 예전 `+760k`대보다는 줄었다.
  - 아직도 대부분이 inferred DoT 쪽이다.

### April heavy2 fight3 / Dragoon / 64AC
- 이전 확인값:
  - `emittedTotal=2826777`
  - `raw21Total=1036593`
  - `inferredDotTotal=1790184`
  - `fflogsAbilityTotal=2063388`
  - `delta=+763389`
- 주의:
  - 이 값은 corroborated known-source 경로 추가 전 기준이다.
  - 변경 후 재측정은 이번 턴에 Gradle 캐시 잠금 때문에 아직 확정하지 못했다.

## 지금까지 확정된 핵심 사실

### 공통 병목
- 문제는 `status=0 DoT ownership`이다.
- 특히 multi-target 전투에서 아래 조건이 반복된다.
  - target active set이 넓다.
  - `activeCoverage=1.0`이라 active subset이 전혀 좁혀지지 않는다.
  - 하지만 target snapshot fallback set에는 정답 key가 없다.
  - 그래서 generic split이 타직업 DoT set으로 과귀속된다.

### heavy2 old/new 공통 문제 타깃
- `레드 핫`
- `딥 블루`
- `수중 감옥`

### 중요한 진단 결론
- `guidPresent=false`인 tick이 실제 병목이다.
- 이 케이스는 “같은 key가 있는데 weight만 틀린 것”이 아니라 “fallback 후보군에 정답 key 자체가 없는 것”에 가깝다.
- 그래서 단순 weight 조정이나 우선순위 조정만으로는 한계가 있다.

## 실패한 시도

### 실패 1. known-source generic fallback 전역 차단
- 결과:
  - `ActIngestionServiceTest` 실패
  - heavy2 all-fights gate 실패
- 판단:
  - 전역 clamp는 금지

### 실패 2. known-source를 `accepted_by_source`로 우선
- 결과:
  - 일부 tick은 action 해석이 맞아도 전체 parity가 더 악화됐다.
  - 특히 April heavy2 `DRG 64AC`가 더 나빠졌다.
- 판단:
  - `accepted_by_source`는 너무 느슨하다.

### 실패 3. party-source multi-target 전역 차단
- 결과:
  - rollup과 heavy2 fight1이 크게 깨졌다.
- 판단:
  - heavy2 fight1에도 같은 구조가 작게 존재하므로, 단순 전역 차단은 안 된다.

## 다음에 바로 이어서 할 일

### 1. April heavy2 fight3 / DRG 64AC 재측정
- 목표:
  - 이번에 추가한 `status0_corroborated_known_source`가 April DRG를 실제로 줄였는지 확인
- 우선 확인할 것:
  - `debugHeavy2AprilFight3DragoonDirectVsDot_prints64acDecomposition`
  - 필요하면 `debugHeavy2AprilFight3DragoonActiveSubsetLeak_printsSourceMatchedTargets`
  - 필요하면 `debugHeavy2AprilFight3DragoonAcceptedBySourcePotential_printsResolvedActions`
- 메모:
  - Windows 캐시 잠금이 있으면 먼저 `compileJava` 후 같은 `GRADLE_USER_HOME`으로 순차 실행

### 2. April DRG가 여전히 안 줄면 다음 좁은 가설로 간다
- 후보:
  - corroborated known-source 경로에 `evidence age` 조건 추가
  - corroborated known-source 경로에 `same target lifecycle freshness` 조건 추가
- 하지 말 것:
  - `accepted_by_source` 전역 우선화
  - known-source generic fallback 전역 차단

### 3. 최종 방향
- 계속 미세 보정보다 `source-target-action` 복원을 더 FFLogs식으로 강화한다.
- 다음 구조 확장 후보:
  - same source + same target의 recent application/status를 묶는 `active dot instance` 성격 강화
  - generic split 전에 복원 가능한 tick을 더 확실히 복원

## 다음 턴 시작 체크리스트
- 1. `tasks.md`와 `AGENTS.md` 확인
- 2. `ActIngestionService.java`의 현재 살아 있는 변경 확인
  - `shouldSuppressUnknownMultiTargetFallback(...)`
  - `shouldSuppressKnownSourceGuidMissingMultiTargetFallback(...)`
  - `shouldPreferCorroboratedKnownSourceAttribution(...)`
- 3. `compileJava`
- 4. `ActIngestionServiceTest`
- 5. `SubmissionParityRegressionGateTest`
- 6. April heavy2 DRG diagnostics 재측정

## 건드려도 되는 원칙
- FFLogs식 복원에 방해되는 불필요한 heuristic은 제거 가능
- 단, gate를 깨는 전역 규칙 변경은 유지 금지
- explainability 없는 fallback 추가 금지

## 참고 파일
- `AGENTS.md`
- `docs/parity-patch-notes.md`
- `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`
- `src/main/java/com/bohouse/pacemeter/application/UnknownStatusDotAttributionResolver.java`
- `src/test/java/com/bohouse/pacemeter/application/SubmissionParityReportDiagnostics.java`
- `src/test/java/com/bohouse/pacemeter/application/SubmissionParityRegressionGateTest.java`
