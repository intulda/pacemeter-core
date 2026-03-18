# paceMeter 작업 목록

## 현재 작업: 보스 체력 기반 클리어 가능 여부 판단 시스템

**목표**: ACT에서 보스 HP 정보를 받아서, 현재 파티 DPS로 엔레이지 전에 보스를 잡을 수 있는지 판단

**핵심 가치**: FFLogs 데이터 없이도 동작해서 신규 레이드 첫날부터 사용할 수 있어야 함

---

## 현재 상태 요약

### 구현 완료
- ACT `03` AddCombatant 라인에서 `currentHp`, `maxHp` 파싱
- 보스 후보 감지 및 `BossIdentified` 이벤트 발행
- `CombatState`에 `BossInfo` 저장
- `EnrageTimeProvider` 포트 정의
- `CactbotFileMapping` 구현 및 리소스 매핑 구성
- `CactbotTimelineProvider` 구현
- `CombatService -> SnapshotAggregator -> OverlaySnapshot` clearability 계산 경로 연결
- `ClearabilityCheck` 계산 로직 구현
- raw replay 기반 보스 식별/clearability 통합 테스트 추가
- clearability 경계조건 테스트 보강
- `CactbotTimelineProvider` 실다운로드 통합 테스트 추가

### 현재 진행 중
- JSONL replay 기반 clearability 시나리오 추가 검증
- rDPS 정밀도 개선: DoT snapshot 모델 보강 및 버프/디버프 검증 확대

### heavy4 parity 진행 메모
- 기준 submission: `2026-03-15-heavy4-vafpbaqjnhbk1mtw`
- 기준 원본 raw: `src/main/resources/Split-Network_30007_20260315-2026-03-15T083420.543Z.log`
- submission 로그는 FFLogs report `VAfPBaqJnHbK1Mtw`에 대응하는 실제 ACT raw이며, selected fight는 `fight 2`로 고정한다.
- anonymize 스크립트는 skill/status/name까지 `PlayerXX`로 바꾸는 문제가 있었고, replay 전 역매핑 복원을 이미 넣어 둠.
- heavy4 포렌식성 코드는 일반 회귀 테스트에서 분리했고, 회귀 테스트에는 parser/ingestion regression만 남긴 상태.

#### 현재 확정된 결론
- `409C`, `9094`, `5EFA`는 ordinary direct damage로 보기 어렵다.
  - raw `21`의 damage 필드가 각각 `7670000`, `F2B0000`, `A380000` 형태이며 status apply marker 성격이 강함.
  - ACT Definitions 기준으로도 `0767=Biolysis`, `0F2B=Baneful Impaction`, `0A38=Eukrasian Dosis III` status와 연결된다.
- heavy4 fight 2 boss `24|DoT|0`는 raw에 실제로 많이 존재한다.
  - source는 찍히지만 status는 비어 있고, source만 믿고 해석하면 오차가 커진다.
- `38` snapshot float를 가중치로 써서 `SCH/SGE` DoT를 일부 복원하는 경로는 유효했다.
  - 이 경로로 `SCH/SGE` delta가 크게 줄었음.
- 반면 `38` snapshot을 synthetic buff apply처럼 메인 attribution 경로로 쓰는 시도는 과보정이었다.
  - 해당 경로는 제거한 상태를 유지한다.
- 남은 수백 단위 오차 중 상당수는 damage 누락이 아니라 crit/direct-hit attribution 편향이었다.
  - `CombatState.estimateUnbuffedRate()`는 현재 버프량을 fight-wide 관측치에서 빼지 않고, 안정 baseline(`DEFAULT_CRIT_RATE`, `DEFAULT_DIRECT_HIT_RATE`)을 사용하도록 수정함.

#### 현재 안정 기준 parity
- heavy4 `fight 2` 현재 delta:
  - `후엔 / SCH`: `-1404.3`
  - `나성 / SGE`: `-796.6`
  - `이끼이끼 / PCT`: `+798.5`
  - `생쥐 / NIN`: `-530.1`
  - `한정서너나좋아싫어 / DNC`: `+462.4`
  - `치삐 / DRG`: `-450.5`
  - `섬세 / DRK`: `+152.4`
  - `재의 / PLD`: `+63.6`

#### 지금 남은 핵심 문제
- `SCH/SGE`는 아직 `totalDamage` 부족이 남아 있다.
  - `SCH`: `Biolysis`, `Baneful Impaction`
  - `SGE`: `Eukrasian Dosis III`
- `PCT/NIN/DNC/DRG`는 damage 총량보다 buff contribution 편향이 더 크다.
- `DoT#0`는 parity debug skill breakdown 집계 착시가 섞여 있을 수 있다.
  - 현재 skill breakdown은 엔진의 최종 redistributed event가 아니라 raw `24`를 다시 `resolveDotActionId()`로만 집계한다.

#### 다음 우선순위
1. `type 37` 파서를 추가한다.
2. `0767 / 0F2B / 0A38`만 대상으로 `21 application marker + 37 result`를 연결해 DoT lifecycle 복원을 시도한다.
3. `37` 기반 복원 후 heavy4 `fight 2` 8인 parity를 다시 측정한다.
4. 그 다음에 `PCT/NIN/DNC/DRG`의 남은 수백 단위 buff attribution 편향을 status별로 다시 본다.

#### 2026-03-18 추가 메모
- `37` parser + ingestion fallback을 production 경로에 붙이는 실험은 롤백했다.
- 증상:
  - local replay total damage가 전 직업에서 대략 절반 수준으로 붕괴
  - replay 응답 예시: `parsedLines=19316`, `phase=ENDED`
  - 로그상 `receivedAbilityCount=3980`, `emittedDamageCount=1307`
  - replay 종료 직전 `combat timeout (166736ms idle)`가 발생
- 해석:
  - `37` 자체가 정답 축일 가능성은 남아 있지만, 현재 replay/window/timeout 경로와 직접 연결하면 부작용이 너무 크다.
  - 특히 parity를 깨지 않으면서 `37`을 써야 하므로, 다음에는 production ingestion이 아니라 offline diagnostics로만 먼저 검증해야 한다.
- 현재 코드 상태:
  - `37` parser/ingestion production 반영은 제거
  - `SCH 9094/F2B`, `SGE 5EFA/A38` unknown-status mapping 보강은 유지
  - parser/ingestion targeted tests는 통과
  - `SubmissionParityReport`에 `parityQuality` 요약 필드 추가:
    - `meanAbsolutePercentageError`, `p95AbsolutePercentageError`, `maxAbsolutePercentageError`
    - `outlierActorCount`, `within{1,3,5}%` 비율
    - matched/unmatched actor 카운트
  - 앞으로 heavy4/다직업 비교는 `comparisons` 수동 점검 + `parityQuality` 지표를 함께 본다.
  - 신규 집계 경로 추가:
    - `SubmissionParityQualityService`가 `data/submissions/*`를 스캔해 submission별/전체 품질 지표를 집계
    - debug API: `/api/debug/parity/quality`
    - gate/우선순위 필드 추가:
      - gate 기준: `p95<=3%`, `max<=10%`, `outlier(>5%)<=5%`
      - `worstActors` 상위 20개를 함께 반환해 다음 수정 타깃을 자동 식별
      - `jobs` 롤업 추가:
        - job별 `MAPE/p95/max/outlier/within1/3/5/pass`를 계산
        - 전 직업 관점에서 어떤 직업군이 구조적으로 흔들리는지 우선순위 식별 가능

### 확인 필요
- 프론트엔드 저장소 경로: `/Users/kimbogeun/WebstormProjects/pacemeter-overlay`
- clearability UI 렌더링은 프론트 저장소에서 이미 연결되어 있음
- 이후 프론트 관련 작업은 위 저장소 기준으로 진행

---

## Phase 1: 보스 HP 파싱 (백엔드)

### 완료
- `CombatantAdded`에 `currentHp`, `maxHp` 필드 추가
- `ActLineParser`에서 AddCombatant HP 파싱 추가
- `CombatEvent`에 `BossIdentified` 이벤트 추가
- `CombatState`에 `BossInfo` 상태 추가
- `CombatEngine`에서 `BossIdentified` 이벤트 반영
- `ActIngestionService`에 보스 후보 감지 및 전투 시작 후 `BossIdentified` 발행 로직 추가
- 관련 단위 테스트 추가
- 헤비급 3층 1풀 raw replay fixture 추출 스크립트 및 결과 파일 생성
- `heavy3_pull1_minimal.log` 기반 raw replay 통합 테스트 추가

### 📋 남은 작업
- [ ] Phase 1 마무리 검토: JSONL replay 시나리오 추가 보강

---

## Phase 2: 엔레이지 정보 제공자 (백엔드)

### 완료
- [x] **EnrageTimeProvider 인터페이스 정의**
  - `Optional<EnrageInfo> getEnrageTime(int territoryId)`
  - `record EnrageInfo(double seconds, ConfidenceLevel confidence, String source)`
  - 파일: `src/main/java/com/bohouse/pacemeter/application/port/outbound/EnrageTimeProvider.java`

- [x] **CactbotFileMapping 구현**
  - Territory ID → Cactbot 파일 경로 매핑 테이블
  - Light-Heavyweight, Cruiserweight, Heavyweight, Endwalker Savage, Ultimate 포함
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/outbound/cactbot/CactbotFileMapping.java`

- [x] **CactbotTimelineProvider 구현**
  - GitHub Raw URL로 타임라인 다운로드
  - 정규식으로 `(enrage)` 라인 파싱
  - 메모리 캐싱 (`ConcurrentHashMap`)
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/outbound/cactbot/CactbotTimelineProvider.java`

- [x] **Spring 빈 설정**
  - `RestTemplate` 빈 추가
  - `EnrageTimeProvider` 주입 경로 연결
  - 파일: `src/main/java/com/bohouse/pacemeter/config/AppWiringConfig.java`

- [x] **테스트 작성**
  - CactbotFileMapping 테스트
  - CactbotTimelineProvider 파싱 단위 테스트
  - Enrage 파싱 단위 테스트

### 📋 남은 작업
- [ ] **로컬 캐싱 (선택사항)**
  - `~/.pacemeter/cactbot-cache/` 디렉토리 저장
  - 오프라인 대응

---

## Phase 3: 클리어 가능 여부 계산 (백엔드)

### 완료
- [x] **ClearabilityCheck record 정의**
  - `canClear`, `estimatedKillTimeSeconds`, `enrageTimeSeconds`, `marginSeconds`
  - `requiredDps`, `confidence`
  - 파일: `src/main/java/com/bohouse/pacemeter/core/snapshot/ClearabilityCheck.java`

- [x] **SnapshotAggregator 수정**
  - `aggregate()`에 `Optional<EnrageInfo>` 파라미터 추가
  - 보스 정보 + 엔레이지 정보가 있으면 clearability 계산
  - 파일: `src/main/java/com/bohouse/pacemeter/core/snapshot/SnapshotAggregator.java`

- [x] **OverlaySnapshot에 clearability 필드 추가**
  - 파일: `src/main/java/com/bohouse/pacemeter/core/snapshot/OverlaySnapshot.java`

- [x] **CombatService 수정**
  - `EnrageTimeProvider` 주입
  - tick 시 엔레이지 정보 조회 후 SnapshotAggregator에 전달
  - 파일: `src/main/java/com/bohouse/pacemeter/application/CombatService.java`

- [x] **계산 로직 검증**
  - `ClearabilityCheck.calculate()` 단위 테스트
  - raw replay 기반 clearability 통합 테스트
  - 보스 없음 / 엔레이지 없음 / FightEnd 스냅샷 경계조건 테스트

### 📋 남은 작업
- [ ] JSONL replay 기반 실제 시나리오 추가 검증
- [ ] 보스 현재 HP를 사용할지, max HP 기준 단순 kill-time 추정을 유지할지 계산 모델 고도화 검토

---

## Phase 4: UI 표시

### 상태
- 프론트 저장소 경로: `/Users/kimbogeun/WebstormProjects/pacemeter-overlay`
- 백엔드 snapshot 필드는 준비됨
- `clearability` 타입/매핑/HUD 렌더링은 이미 구현됨

### 남은 작업
- [x] 프론트엔드 저장소/경로 확정
- [x] `OverlaySnapshot.clearability` 타입 연결
- [x] HUD 통합 및 상태별 스타일링
- [x] `requiredDps`는 HUD 주지표에서 제외 유지
- [ ] 데이터 없음 / 보스 없음 / LOW confidence 상태 실제 플레이 검증

### 실사용 검증 체크리스트
- [ ] ACT live 연결 상태에서 전투 시작 직후 `LOW` confidence가 정상 표시되는지 확인
- [ ] 보스 식별 전에는 clearability 패널이 표시되지 않는지 확인
- [ ] 엔레이지 매핑이 없는 인스턴스에서는 clearability가 숨겨지는지 확인
- [ ] 전멸/와이프 종료 시 마지막 스냅샷에 clearability가 유지되는지 확인
- [ ] 보스가 있는 실제 레이드에서 예상 킬타임 / 엔레이지 / 여유 시간이 상식적인 범위인지 확인

---

## 후속 작업

### 백엔드
- [ ] 레이드 버프/디버프 ID 카탈로그 실제 값 검증 확대
- [x] FFLogs parity 비교용 첫 실제 전투 로그 샘플 등록
- [ ] FFLogs parity 비교용 실제 전투 로그 수집 확대
- [x] 수집 로그 메타데이터/익명화/SQLite 카탈로그 워크플로우 추가
- [x] 등록 submission 기준 로컬 parity 초안 리포트 API 추가
- [x] parity 리포트에 FFLogs report summary 상태 필드 연결
- [x] parity 리포트에 FFLogs actor table 연결 및 fight window 기준 로컬 replay 정합
- [ ] FFLogs `table.totalRDPS` 단위 해석 검증 및 paceMeter `onlineRdps` 비교 축 정정
- [ ] Encounter 자동 매칭 개선 (`targetName -> encounter`)
- [ ] FFLogs API 캐싱 및 성능 최적화
- [ ] 에러 핸들링 강화 (rate limit, 네트워크 오류)
- [ ] 로깅 개선 (전투 종료 시 요약 로그)
- [ ] 히스토리 저장 기능 (SQLite/H2)
- [ ] 통계 API (`GET /api/stats`)
- [ ] 관리 엔드포인트 (`POST /api/combat/reset` 등)

### 프론트엔드
- [ ] 직업 아이콘 추가
- [ ] 성능 최적화
- [ ] 전투 종료 시 최종 결과 화면
- [ ] 에러 표시
- [ ] 히스토리 뷰

### 디버그/운영성
- [x] 현재 전투 rDPS 분해 디버그 API 추가
- [x] 원격 테스트용 ACT relay 경로 추가

---

## 체크 포인트

1. 백엔드 목표는 구현보다 검증 강화 단계에 들어옴
2. clearability 백엔드는 거의 마감 단계이며 남은 일은 실제 플레이/JSONL 검증
3. rDPS 정밀도 개선은 별도 후속 스트림으로 계속 진행
