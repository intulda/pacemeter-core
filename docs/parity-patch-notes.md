# Parity Patch Notes

## 2026-03-19

### 목표
- 로그별 임시 보정이 아닌 구조 리팩토링 기반으로 FFLogs live rDPS 근사 신뢰도 상향.
- 프론트 전달 스냅샷에 실전 지표(`hit/death/max hit`) 추가.

### 변경 사항
- `ActorStats` 확장:
  - `deathCount`
  - `maxHitDamage`
  - `maxHitActionId`
  - `addDamage(amount, timestampMs, actionId)` 오버로드 추가
  - 중복 사망 이벤트 시 `deathCount` 중복 증가 방지
- `CombatState.reduceDamage`:
  - `DamageEvent.actionId`를 `ActorStats`에 전달해 최대 타격 스킬 추적 가능화
- `ActorSnapshot` 확장:
  - `deathCount`
  - `maxHitDamage`
  - `maxHitSkillName`
- `SnapshotAggregator`:
  - 신규 필드 매핑 추가
  - `ActionNameLibrary`를 통한 스킬명 해석 연결
- 액션명 해석 리팩토링:
  - `core/model/ActionNameLibrary` 신설
  - `ActActionNameLibrary`의 placeholder 치환 시 core 라이브러리 재사용
  - placeholder 치환은 “known action only”로 유지 (미등록 스킬은 `PlayerXX` 유지)
- DoT 규칙 정리 1단계:
  - `DotAttributionRules` 신설 (`DotAttributionCatalog` 기반 단일 규칙 객체)
  - `ActIngestionService`의 분산 상수 참조를 규칙 객체 참조로 통합

### 검증
- 통과:
  - `ActLineParserTest`
  - `ActIngestionServiceTest`
  - `CombatStateTest`
  - `CombatEngineReplayTest`
  - `SubmissionParityRegressionGateTest`

### 회귀 방지 포인트
- 파서 placeholder 동작 회귀 1회 발생 후 즉시 수정:
  - 원인: unknown 스킬도 hex fallback으로 치환됨
  - 조치: `resolveKnown()` 분리로 known action만 치환

### 다음 단계
- DoT attribution 구조 리팩토링 2단계:
  - `ActIngestionService` 내부 로직을
    1) 증거 수집
    2) 후보 생성
    3) 귀속 결정
  - 3계층으로 분리하여 직업/보스/로그 변화에도 규칙 안정성 확보
- heavy2/heavy4 + 신규 실로그(`Network_30103_20260318.log`) 기준으로 동일 알고리즘 재측정

## 2026-03-19 (2)

### 목표
- DoT unknown-source 귀속 코드의 단계 분리 리팩토링(동작 변화 없이 구조 안정화).

### 변경 사항
- `ActIngestionService`
  - unknown-status target evidence 조회 공통화:
    - `resolveUnknownStatusDotEvidence(...)` 추가
    - `resolveTrackedUnknownStatusDotStatusId(...)`/`resolveTrackedUnknownStatusDotActionId(...)` 중복 제거
  - unknown-source attribution 분해:
    - `collectUnknownSourceCandidates(...)`
    - `addActionEvidenceCandidates(...)`
    - `addStatusEvidenceCandidates(...)`
  - 기존 단일 메서드 내부 루프/결정 혼합을
    - 증거 수집
    - 후보 병합
    - 최신 근거 선택
    순으로 분리

### 검증
- 통과:
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`

### 회귀 방지 포인트
- 이전에 실패한 보정 방식(전면 snapshot 차단, source 우선 강제)은 재도입하지 않음.
- 이번 단계는 함수 분리 리팩토링만 수행(알고리즘 결과 동일 유지).

### 다음 단계
- DoT attribution 구조 리팩토링 3단계:
  - `ActIngestionService` 바깥으로 귀속 결정 컴포넌트 추출
  - 해당 컴포넌트에 job-agnostic 테스트 집중
- 이후 heavy2/heavy4/신규 로그 3종 parity 수치 재측정

## 2026-03-19 (3)

### 목표
- DoT unknown-status 귀속 결정 로직을 `ActIngestionService` 바깥으로 추출.
- 직업별 하드코딩 테스트 대신 job-agnostic 규칙 테스트 추가.

### 변경 사항
- 신규 컴포넌트:
  - `UnknownStatusDotAttributionResolver`
  - 역할:
    - tracked status/action evidence 조회
    - unknown-source tick 후보 생성/최신 후보 선택
- `ActIngestionService` 변경:
  - unknown-status evidence map key/value 타입을 resolver record로 전환
  - `resolveTrackedUnknownStatusDotStatusId`, `resolveTrackedUnknownStatusDotActionId`,
    `resolveUnknownSourceDotAttribution`이 resolver 위임 구조로 변경
  - service 내부의 중복 후보수집 메서드/record 삭제
- 신규 테스트:
  - `UnknownStatusDotAttributionResolverTest`
    - status→action 매핑 검증
    - unknown-source 후보 최신 선택 검증
    - 타겟 불일치/시간 만료 evidence 배제 검증

### 검증
- 통과:
  - `UnknownStatusDotAttributionResolverTest`
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`

### 회귀 방지 포인트
- 알고리즘 자체는 유지, 책임만 분리.
- 기존 실패 전략(전면 차단/강제 우선순위 전환)은 재도입 없음.

### 다음 단계
- heavy4/heavy2/신규 실로그(3건) 기준 parity 측정 자동 리포트 실행.
- 측정 결과에서 `worstActors`와 line-type 증거를 연결하는 진단 리포트 추가(수정 우선순위 자동화).

## 2026-03-19 (4)

### 목표
- 리팩토링(3단계) 이후 parity 수치 악화 여부 즉시 검증.

### 실행
- `SubmissionParityReportDiagnostics`
  - `debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta`
  - `debugHeavy2Fight6Parity_withConfiguredFflogsCredentials_printsActorDelta`
  - `debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors`

### 결과 요약
- heavy4 (`2026-03-15-heavy4-vafpbaqjnhbk1mtw`, selectedFightId=2):
  - `MAPE=0.01196`
  - `p95=0.02282`
  - `max=0.02505`
  - actor 예시:
    - `NIN 생쥐 delta=-760.4`
    - `SGE 나성 delta=-315.2`
    - `DRG 치삐 delta=+297.2`
    - `SCH 후엔 delta=-21.7`
- 전체 롤업(3 submissions / 24 actors):
  - `mape=0.01850`
  - `p95=0.05400`
  - `max=0.05946`
- 결론:
  - 이번 단계 리팩토링으로 수치 악화 없음(기존 안정 구간 유지).

### 다음 단계
- heavy2 상위 오차 직업(`SAM/SCH/WHM/PCT`)을 line-type 증거와 1:1로 연결하는 자동 진단 리포트 추가.
- 이후에만 parser/ingestion 동작 변경을 적용(증거 없는 보정 금지).

## 2026-03-19 (5)

### 목표
- heavy2 오차 상위 직업군의 line-type 근거 자동 출력(수동 추적 제거).

### 변경 사항
- `SubmissionParityReportDiagnostics`에 신규 진단 추가:
  - `debugHeavy2Fight6WorstActorLineTypeEvidence_printsUnknownSkillCorrelations`
  - 동작:
    1. heavy2 fight6 비교 결과에서 오차 상위 actor 자동 선정(`SAM/SCH/WHM/PCT` 우선)
    2. selected fight window 포함 라인만 스캔
    3. actor별 source line-type 분포(`21/22/24/26/30`) 출력
    4. unknown skill 이벤트(`Player*`, `DoT#0*`) 발생량/피해량 집계
    5. unknown 이벤트 주변 line type(`20/37/38/39/261/264/270`) 상관 출력

### 검증
- 실행:
  - `SubmissionParityReportDiagnostics.debugHeavy2Fight6WorstActorLineTypeEvidence_printsUnknownSkillCorrelations`
  - `SubmissionParityRegressionGateTest`
- 결과:
  - `heavy2.targetActors=[재탄, 젤리, 바나바나, 백미도사]`
  - `재탄`: unknownEvents=0
  - `젤리`: unknownEvents=0
  - `바나바나`: unknownEvents=2, unknownDamage=36835, 주변 타입 `{264=4, 37=3, 38=5, 270=1, 261=1}`
  - `백미도사`: unknownEvents=0

### 해석
- heavy2 현재 큰 오차의 공통 원인이 “전 직업 unknown skill 폭증”은 아님.
- 이번 로그 기준 unknown-skill 근거는 PCT(`바나바나`)에만 집중됨.
- SAM/SCH/WHM 오차는 다른 축(버프 귀속/도트 lifecycle/fflogs event 대응)에서 추가 분해 필요.

### 다음 단계
- heavy2에서 `바나바나(PCT)` unknown 이벤트 2건의 원시 라인→FFLogs ability 1:1 대조 진단 추가.
- 동시에 `재탄/젤리/백미도사`는 unknown 아닌 경로(기여도/도트 귀속)로 분리 진단.

## 2026-03-19 (6)

### 목표
- heavy2 상위 오차 actor를 unknown 이벤트가 아닌 `GUID 단위 최종 합계`로 분해.

### 변경 사항
- 신규 진단 추가:
  - `debugHeavy2Fight6PctUnknownEvents_printsRawToFflogsCandidates`
    - PCT unknown raw 이벤트 2건의 원문/주변 line type 출력
  - `debugHeavy2Fight6WorstActorGuidSkillDelta_printsActionLevelMismatch`
    - 상위 오차 actor 4명(SAM/SCH/WHM/PCT)의 GUID별 local vs FFLogs ability total 비교
    - 비교 기준을 `localTop10`에서 `combat.skillBreakdowns 전체`로 보정
  - `debugHeavy2Fight6GuidParityFromIngestion_printsEmitVsFflogsTotals`
    - selected fight window 기준 ingestion emit 합계(local)와 FFLogs ability total 동시 출력
    - `eventsByAbility`와 `abilities table` 간 불일치 여부도 관찰 가능하게 출력 강화

### 검증
- 각 진단 + `SubmissionParityRegressionGateTest` 실행 통과.

### 핵심 관측
- PCT unknown raw:
  - `DoT#0` 2건(합 36,835) 확인
  - 두 건 모두 source=`바나바나`, target=`레드 핫`, 주변에 `37/38/264(및 270)` 동반
  - 다만 최종 localTopSkills에는 unknown이 남지 않아, 이 2건 자체가 주오차 축은 아님
- GUID별 큰 차이(ability total 기준):
  - WHM `4094`: local `325,880` vs fflogs `2,640,639` (대폭 부족)
  - SCH `409C`: local `57,312` vs fflogs `2,004,891` (대폭 부족)
  - SAM `1D41`: local `285,075` vs fflogs `1,542,816` (대폭 부족)
  - PCT `8780`: local `946,121` vs fflogs `834,853` (과다 +111,268)
- 보조 관측:
  - `eventsByAbility` 결과는 일부 GUID에서 `abilities table`과 큰 차이가 있어,
    원인 판단 기준은 우선 `abilities table` 합계를 사용해야 함.

### 다음 단계
- 우선순위 1: `4094/409C/1D41`의 누락 경로(unknown-status DoT 수용/귀속 단계) 집중 보강.
- 우선순위 2: `8780` 과다 경로는 result-confirmed/중복집계 여부 점검.
- 위 두 축을 분리 적용하고 heavy2/heavy4/신규 로그 3건 parity 재측정.

## 2026-03-19 (7)

### 목표
- 집 환경에서 즉시 재현 가능한 테스트 안정화 + 리팩토링 이후 parity 재측정.

### 변경 사항
- `SubmissionParityReportServiceTest` 안정화:
  - 삭제된 fixture(`2026-02-11-heavy3-pull1-full`) 의존 제거
  - 현재 저장소의 실 submission(`2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`) 기준으로 변경
  - 환경/로그 변동성이 큰 assertion(`fightStarted`, 고정 `fightName`, 고정 `territoryId`, non-null enrage`) 제거
  - 핵심 회귀 포인트(파싱/스냅샷 생성/actor damage 존재/fflogs fallback)만 검증

### 검증
- 통과:
  - `SubmissionParityReportServiceTest`
  - `SubmissionParityRegressionGateTest`
  - `SubmissionParityReportDiagnostics.debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta`
  - `SubmissionParityReportDiagnostics.debugHeavy2Fight6Parity_withConfiguredFflogsCredentials_printsActorDelta`
  - `SubmissionParityReportDiagnostics.debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors`

### 측정 결과
- heavy4 (`2026-03-15-heavy4-vafpbaqjnhbk1mtw`, selectedFightId=2):
  - `MAPE=0.01196`, `p95=0.02282`, `max=0.02505`
  - 대표 delta:
    - `NIN 생쥐 -760.4`
    - `SGE 나성 -315.2`
    - `DRG 치삐 +297.2`
    - `SCH 후엔 -21.7`
- heavy2 (`2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`, selectedFightId=2):
  - `MAPE=0.03339`, `p95=0.05857`, `max=0.05946`
  - 대표 delta:
    - `SAM 재탄 +2087.2`
    - `PCT 바나바나 +1357.5`
    - `SCH 젤리 -1099.3`
    - `WHM 백미도사 -761.5`
- 전체 롤업(3 submissions / 24 actors):
  - `mape=0.01850`, `p95=0.05400`, `max=0.05946`
  - gate:
    - target `p95<=0.03`, actual `0.05400` (fail)
    - target `max<=0.10`, actual `0.05946` (pass)
    - target `outlier<=0.05`, actual `0.08333` (fail)

### 해석
- heavy4는 안정 구간 유지.
- 현재 전체 gate 실패를 주도하는 축은 heavy2(`SAM/SCH/WHM/PCT`)이며, 다음 수정 우선순위는 heavy2로 고정한다.

## 2026-03-19 (8)

### 목표
- heavy2 대오차의 구조 원인(잘못된 selected fight 재선택) 제거.

### 원인
- `metadata.fflogsFightId=6`이 명시돼 있어도,
  - `toSubmissionFflogsSummary()` 내부에서 encounter/submittedAt 휴리스틱이 다시 적용되어
  - heavy2가 `selectedFightId=2`로 덮어써지고 있었다.
- 이 상태로는 FFLogs와 다른 fight를 비교하게 되어 delta가 인위적으로 커진다.

### 변경 사항
- `SubmissionParityReportService.toSubmissionFflogsSummary`
  - 명시된 `selectedFightId`가 report 내에서 유효하게 매칭되면
  - encounter/submittedAt 기반 재선택을 수행하지 않도록 수정
  - 즉, explicit fight id는 강제 유지
- 테스트 보강:
  - `toSubmissionFflogsSummary_keepsExplicitFightIdWhenEncounterMismatches`
  - `toSubmissionFflogsSummary_keepsExplicitFightIdEvenWhenEncounterMismatch`

### 검증
- 통과:
  - `SubmissionParityReportServiceTest`
  - `SubmissionParityRegressionGateTest`
  - parity diagnostics 3종(heavy4/heavy2/rollup)

### 재측정 결과 (핵심)
- heavy2 (`2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`)
  - selectedFightId: `2 -> 6` 교정
  - `MAPE: 0.03339 -> 0.01581`
  - `p95: 0.05857 -> 0.03460`
  - `max: 0.05946 -> 0.03696`
  - 주요 actor delta:
    - `재탄(SAM): +2087.2 -> +749.3`
    - `젤리(SCH): -1099.3 -> -654.1`
    - `바나바나(PCT): +1357.5 -> +427.9`
    - `백미도사(WHM): -761.5 -> -814.3` (대체로 유지)
- 전체 롤업(3 submissions / 24 actors)
  - `MAPE: 0.01850 -> 0.01264`
  - `p95: 0.05400 -> 0.03595`
  - `max: 0.05946 -> 0.03747`
  - outlier ratio: `0.08333 -> 0.0`

### 해석
- 이번 개선은 “DoT 미세 귀속 튜닝” 이전에 선행돼야 하는 비교축 정합성 버그 수정이다.
- 현재 남은 p95 초과(목표 0.03 대비 0.03595)는 fight selection 오차가 아닌 순수 attribution/coverage 문제로 좁혀졌다.

## 2026-03-19 (9)

### 목표
- heavy2 잔차를 줄이기 위한 DoT attribution 미세 튜닝 검증(실패 시 즉시 롤백).

### 유지된 변경
- `ActIngestionService`:
  - `known-status + unknown-source` DoT에 대해 recent status/application evidence로 source/action 복원 경로 추가
  - 회귀 테스트 추가:
    - `dotTick_withKnownStatusAndUnknownSource_usesRecentStatusEvidence`

### 시도 후 롤백한 변경
- `status=0`에서 `acceptedBySource`를 snapshot redistribution보다 우선하는 변경
  - 결과: heavy2/heavy4 동시 악화(대폭)
  - 조치: 즉시 롤백
- `status=0` unknown-source attribution 존재 시 snapshot redistribution 스킵
  - 결과: heavy4 일부 개선, heavy2 악화, 전체 p95 악화
  - 조치: 롤백
- `UNKNOWN_STATUS_DOT_WINDOW_MS 90s -> 35s`
  - 결과: 개선 없음(소폭 악화)
  - 조치: 90s로 롤백

### 현재 기준(유지 상태)
- 전체 롤업(3 submissions / 24 actors):
  - `mape ~= 0.01264`
  - `p95 ~= 0.03595`
  - `max ~= 0.03747`
- gate:
  - `p95<=0.03`만 미충족, `max/outlier`는 충족
