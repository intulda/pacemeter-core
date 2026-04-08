# paceMeter 에이전트 가이드

## 현재 목표
- 최우선 목표는 `live rDPS parity`다.
- 기준은 `pacemeter live rDPS ~= FFLogs companion live rDPS`다.
- replay parity와 regression gate는 목표 자체가 아니라 검증 수단이다.
- clearability, UI, 기타 부가 기능은 메인 스트림이 아니다.

## 지금 기준선
- 현재 오프라인 rollup 기준선:
  - `mape=0.0139001291`
  - `p95=0.0352008647`
  - `max=0.0355029839`
- 현재 selected fight:
  - heavy4 `fight=5`
  - heavy2 `fight=2`
  - lindwurm `fight=8`
- heavy2의 현재 핵심 잔차:
  - `재탄 / 1D41(Higanbana)` 과대
  - `구려 / 64AC(Chaotic Spring)` 과대

## 작업 시작 전
- 항상 저장소의 [`tasks.md`](pacemeter-core/tasks.md)를 먼저 확인한다.
- 최근 parity 메모는 [`docs/parity-patch-notes.md`](pacemeter-core/docs/parity-patch-notes.md)를 기준으로 본다.
- selected fight 하나만 보고 결론내리지 않는다.
- heavy2/heavy4/lindwurm와 heavy2 all-fights gate를 같이 본다.

## 개발 원칙
- FFLogs식으로 간다는 말은 `버프 수학`만이 아니라 `attribution`까지 포함한다.
- `status=0 DoT`는 임의 분배보다 근거 복원이 우선이다.
- live explainability가 약한 규칙은 replay 수치가 좋아 보여도 채택하지 않는다.
- production 변경 전에는 왜 좋아질지 설명 가능해야 한다.
- 설명이 안 되는 heuristic 추가는 금지한다.

## 금지 사항
- selected fight 하나만 맞추는 튜닝 금지
- heavy2 개선을 위해 heavy4/lindwurm를 깨는 변경 금지
- gate 실패 상태 방치 금지
- `status=0` fallback 순서만 감으로 뒤집는 작업 반복 금지
- clearability/UI 작업으로 메인 스트림 이탈 금지
- “FFLogs식 같다”는 추정만으로 구조 변경 정당화 금지

## 현재 병목
- 핵심 병목은 [`ActIngestionService.java`](pacemeter-core/src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java)의 `status=0` DoT attribution이다.
- 현재 특히 문제가 되는 경로:
  - `status0_snapshot_redistribution`
  - `status0_tracked_target_split`
  - `status0_tracked_source_target_split`
- heavy2 fight2에서 `재탄 / 1D41` 과대분은 boss 총량 자체보다 타깃 오염이 더 크다.
  - `레드 핫`, `딥 블루`, `수중 감옥`으로 잘못 붙는다.

## 현재 확인된 사실
- `1D41` 과대의 남은 큰 축은 `status0_tracked_target_split`이다.
- `64AC`는 “못 잡는 문제”보다 shared GUID semantics와 과집계 문제가 더 크다.
- known-source `status=0`를 무조건 더 엄격하게 막는 패치는 gate를 깨뜨렸다.
- 따라서 다음 변경은 전역 clamp가 아니라 evidence 기준 분해여야 한다.

## 작업 순서
1. baseline 확인
   - `ActIngestionServiceTest`
   - `SubmissionParityRegressionGateTest`
   - 필요 시 `debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors`
2. heavy2 진단
   - fight2 `1D41` target parity
   - fight2 `1D41` mode breakdown
   - fight2 `64AC` target/mode breakdown
3. 가설 1개만 변경
   - 변경 범위는 작게 유지
   - 왜 heavy2에만 이득인지, 왜 heavy4/lindwurm를 덜 건드리는지 먼저 설명 가능해야 함
4. 바로 회귀 확인
   - `ActIngestionServiceTest`
   - `SubmissionParityRegressionGateTest`
   - 가능하면 rollup

## 하네스 운영 규약
- 모든 구현 세션은 아래 루프를 강제로 따른다.
  1. `tasks.md`와 `docs/parity-patch-notes.md`의 최신 checkpoint를 먼저 확인한다.
  2. baseline 테스트 상태를 다시 확인한다.
  3. selected fight 진단으로 원인을 분해한다.
  4. explainable한 가설 1개만 production에 반영한다.
  5. 즉시 regression gate와 관련 diagnostics를 다시 확인한다.
- 한 번에 여러 attribution 규칙을 동시에 바꾸지 않는다.
- production 변경 전에 반드시 아래 두 문장을 설명 가능해야 한다.
  - 왜 heavy2에는 이득인지
  - 왜 heavy4/lindwurm/all-fights gate는 덜 건드리는지
- 구현 중 관찰 결과가 initial hypothesis와 어긋나면, 기존 가설을 밀어붙이지 말고 진단 단계로 되돌아간다.

## 응답 포맷 규약
- 작업 결과 보고는 가능하면 아래 순서를 유지한다.
  1. 현재 관찰
  2. 가설
  3. 수정 범위
  4. 검증 결과
  5. 남은 리스크
- "수치가 좋아졌다"만으로 변경을 정당화하지 않는다.
- selected fight 개선을 보고할 때는 heavy4/lindwurm/gate 영향도 함께 적는다.

## 작업 요청 템플릿
- 새 작업은 가능하면 아래 포맷으로 시작한다.
```text
목표:
- 이번 턴에 줄이고 싶은 residual 또는 확인하고 싶은 현상 1개

고정 제약:
- live parity 우선
- selected fight 단일 튜닝 금지
- heavy4/lindwurm/all-fights gate 유지
- explainable attribution만 허용

필수 절차:
1. tasks.md / parity notes 확인
2. baseline 테스트 확인
3. heavy2/heavy4/lindwurm 관련 진단
4. 가설 1개만 수정
5. gate 재검증

완료 조건:
- 변경 이유 설명 가능
- gate 유지
- 남은 리스크 명시
```

## 허용되는 규칙 정리
- 개발을 막는 불필요한 규칙은 제거해도 된다.
- 다만 아래는 유지한다:
  - live parity 우선
  - selected fight 단일 튜닝 금지
  - gate 유지
  - explainable attribution 우선

## 테스트 운영 원칙
- 임시 출력용 테스트, 리포트만 찍는 테스트는 남기지 않는다.
- 실제 회귀를 막는 테스트만 유지한다.
- diagnostics 출력 테스트는 현재 parity 분석에 직접 쓰는 범위만 유지한다.

## 참고 파일
- [`tasks.md`](pacemeter-core/tasks.md)
- [`FFLogs Buff Allocation Math`](pacemeter-core/docs/FFLogs Buff Allocation Math.docx)
- [`docs/parity-patch-notes.md`](pacemeter-core/docs/parity-patch-notes.md)
- [`src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`](pacemeter-core/src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java)
- [`src/test/java/com/bohouse/pacemeter/application/SubmissionParityRegressionGateTest.java`](pacemeter-core/src/test/java/com/bohouse/pacemeter/application/SubmissionParityRegressionGateTest.java)
- [`src/test/java/com/bohouse/pacemeter/application/SubmissionParityReportDiagnostics.java`](pacemeter-core/src/test/java/com/bohouse/pacemeter/application/SubmissionParityReportDiagnostics.java)

## LogLine 참고
- LogLine = 0,
- ChangeZone = 1
- ChangePrimaryPlayer = 2,
- AddCombatant = 3,
- RemoveCombatant = 4,
- AddBuff = 5,
- RemoveBuff = 6,
- FlyingText = 7,
- OutgoingAbility = 8,
- IncomingAbility = 10,
- PartyList = 11,
- PlayerStats = 12,
- CombatantHP = 13,
- NetworkStartsCasting = 20,
- NetworkAbility = 21,
- NetworkAOEAbility = 22,
- NetworkCancelAbility = 23,
- NetworkDoT = 24,
- NetworkDeath = 25,
- NetworkBuff = 26,
- NetworkTargetIcon = 27,
- NetworkRaidMarker = 28,
- NetworkTargetMarker = 29,
- NetworkBuffRemove = 30,
- Debug = 251,
- PacketDump = 252,
- Version = 253,
- Error = 254,
- Timer = 255
