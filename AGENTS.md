# paceMeter 에이전트 가이드

## 프로젝트 개요
- FFXIV 실시간 전투 DPS 페이스 비교 오버레이 툴.
- 주요 흐름: ACT -> 전투 로그 -> rDPS 추정 -> FFLogs TOP 페이스 비교 -> Electron UI 표시.
- 핵심 원칙: 모든 rDPS는 추정치이며, FFLogs live rDPS와 최대한 유사하게 구현한다.
- 핵심 원칙: 에러로 인해 사용자 경험을 망치면 안 된다.

## 작업 시작 전 확인
- 작업 시작 전에 항상 [`tasks.md`](/Users/kimbogeun/Documents/Project/paceMeter/tasks.md)를 확인한다.
- 현재 우선 작업은 보스 체력 기반 클리어 가능 여부 판단 시스템이다.
- 목표는 ACT에서 보스 HP를 받아 현재 파티 DPS로 엔레이지 전에 처치 가능한지 계산하는 것이다.
- FFLogs 데이터가 없어도 동작해야 한다.

## 현재 작업 단계
1. 보스 HP 파싱
2. 엔레이지 정보 제공자 연결
3. 클리어 가능 여부 계산
4. UI 표시

## 현재 작업 메모
- 보스 HP는 ACT AddCombatant(`03` 라인)에서 `p[10] = currentHp`, `p[11] = maxHp`로 파싱한다.
- 엔레이지 정보는 Cactbot 타임라인 파일에서 가져온다.
- 엔레이지 시간은 `(\d+\.\d+)\s+".*\(enrage\)"` 패턴으로 파싱한다.
- Territory ID -> Cactbot 파일 경로 매핑이 필요하다.
- 관련 레이드 그룹은 Light-Heavyweight, Cruiserweight, Heavyweight 기준으로 본다.
- 신뢰도 기준:
  - 1분 미만: `LOW`
  - 1분 이상 3분 미만: `MEDIUM`
  - 3분 이상: `HIGH`

## 아키텍처 원칙
- Hexagonal Architecture를 사용한다: `core`, `application`, `adapter`, `config`.
- `core`는 순수 비즈니스 로직만 두고 Spring 의존성을 넣지 않는다.
- `application`은 서비스와 포트 인터페이스를 가진다.
- `adapter`는 외부 시스템 연동을 담당하며 `core`, `application`에만 의존한다.
- adapter끼리 직접 참조하지 않는다.

## 패키지 역할
- `core/event`: `CombatEvent` 및 전투 이벤트 타입
- `core/model`: `CombatState`, `ActorStats`, `ActorId` 등 불변 도메인 모델
- `core/engine`: 이벤트 기반 상태 전환 로직
- `core/estimator`: `OnlineEstimator`, `PaceProfile`
- `core/snapshot`: `SnapshotAggregator`, `OverlaySnapshot`
- `application`: 서비스와 포트
- `adapter/inbound`: ACT websocket, tick driver, replay 입력
- `adapter/outbound`: FFLogs 클라이언트, overlay websocket publisher

## 코딩 규칙
- 도메인 모델은 가능한 `record` 기반 불변 객체로 만든다.
- 닫힌 도메인은 sealed interface/class를 우선 사용한다.
- `null` 반환보다 `Optional`을 우선한다.
- primitive 값 남용 대신 `ActorId` 같은 value object를 사용한다.
- 클래스를 사용할 때는 가능하면 fully-qualified name 대신 `import`를 사용한다.
- 네이밍 규칙:
  - 이벤트: `AbilityDamage`, `ActorDeath`
  - 포트/인터페이스: `~Port`, `~Provider`, `~Publisher`
  - 어댑터: `ActWsClient`, `FflogsApiClient`, `MvcSnapshotPublisher`
  - 서비스: `application` 계층에만 둔다

## 금지 사항
- `core`에 Spring annotation이나 Spring 의존성을 추가하지 않는다.
- FFLogs API key를 사용자 입력으로 받지 않는다.
- `core`에 mutable 상태를 만들지 않는다.
- FFLogs 연동을 추측으로 구현하지 않는다. 공식 문서를 먼저 확인한다.
- adapter가 다른 adapter를 직접 참조하지 않는다.

## 주요 컴포넌트
- `CombatEngine`: `CombatEvent`를 받아 `CombatState`를 갱신하는 이벤트 기반 상태 머신
- `SnapshotAggregator`: `CombatState`를 UI용 `OverlaySnapshot`으로 변환
- `PaceProfile`: FFLogs TOP 기준 시간별 기대 누적 딜 곡선
- `ActWsClient`: ACT OverlayPlugin websocket 메시지를 받아 이벤트로 변환
- `FflogsApiClient`: FFLogs GraphQL API에서 랭킹과 타임라인 조회

## FFLogs 작업 규칙
- FFLogs 관련 작업 전에는 공식 문서를 먼저 확인한다.
  - https://www.fflogs.com/v2-api-docs/ff/
- GraphQL 쿼리는 필요한 필드만 요청한다.
- 토큰은 매번 재발급하지 말고 저장소에서 캐싱한다.
- 현재 기준으로 API credential은 개발자가 앱 설정으로 관리한다.
- 사용자가 직접 입력하는 UI는 만들지 않는다.
- 저장소가 공개되면 secret은 환경 변수 기반으로 전환한다.
- 한국 서버는 필요한 곳에 `partition: "KR"`를 사용한다.
- 글로벌 서버는 기본적으로 `partition: null`을 사용한다.
- `characterRankings` 호출 시 partition 처리를 명시적으로 고려한다.

## 테스트 규칙
- 전투 시나리오 검증은 JSONL replay 테스트를 우선한다.
- `core` 테스트는 순수 함수/결정적 로직 중심으로 작성한다.
- replay fixture는 `src/test/resources/` 아래에 둔다.
- 대표 fixture 유형은 일반 전투, 파티 전멸, 전투 중간 시작이다.

## 개발 워크플로우
1. `core`에 이벤트와 모델을 정의하거나 수정한다.
2. `CombatEngine`에 상태 전환 로직을 추가한다.
3. adapter에서 외부 데이터를 파싱해 도메인 이벤트로 변환한다.
4. `SnapshotAggregator`에서 UI용 데이터를 구성한다.
5. 테스트를 추가하고, 가능하면 JSONL replay도 같이 검증한다.

## FFLogs 작업 흐름
1. 공식 API 문서로 동작을 확인한다.
2. 필요하면 GraphQL 도구에서 쿼리를 검증한다.
3. `FflogsApiClient` 메서드를 추가하거나 수정한다.
4. 관련 `PaceProfile` 구현을 추가하거나 수정한다.
5. 반복 호출 가능성이 있으면 캐싱을 넣는다.

## 성능 원칙
- 상태 변화가 없으면 불필요한 객체 생성을 피한다.
- 동일한 FFLogs 조회 결과는 캐싱한다.
- 스냅샷이 바뀌지 않았으면 websocket publish를 생략한다.

## 디버깅 메모
- ACT OverlayPlugin 상태 확인: `curl http://127.0.0.1:10501/`
- replay 실행 예시: `curl -X POST http://localhost:8080/replay?file=combat-log.jsonl`
- FFLogs 문제를 추적할 때는 GraphQL query/response를 클라이언트 경계에서 로그로 남긴다.

## 배포 전 체크
- 실제 FFLogs credential 설정 여부 확인
- `./gradlew test` 실행
- JSONL replay 시나리오 확인
- 실제 ACT 연결 확인
- 프론트엔드 websocket 연결 확인

## 커밋 규칙
- Conventional Commits 사용: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`

## 참고 링크
- [`tasks.md`](/Users/kimbogeun/Documents/Project/paceMeter/tasks.md)
- FFLogs API: https://www.fflogs.com/v2-api-docs/ff/
- OverlayPlugin: https://github.com/OverlayPlugin/OverlayPlugin
- Cactbot: https://github.com/OverlayPlugin/cactbot
- ACT_Plugin: https://github.com/ravahn/FFXIV_ACT_Plugin
- XIV.dev Actions/Game Internals: https://xiv.dev/game-internals/actions?q=
- Advanced Combat Tracker API Docs: https://advancedcombattracker.com/apidoc/html/N_Advanced_Combat_Tracker.htm
- ACTWebSocket: https://github.com/zcube/ACTWebSocket

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
