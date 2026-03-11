# paceMeter 개발 가이드

## 프로젝트 개요
FFXIV 실시간 전투 DPS 페이스 비교 오버레이 툴.
ACT → 전투 로그 → rDPS 추정 → FFLogs TOP 페이스 비교 → Electron UI 표시

**핵심 원칙**: 모든 rDPS는 추정치이며 FFLogs live rDPS와 최대한 유사하게 구현

---

## 📋 현재 작업

**진행 중인 작업 내용은 `tasks.md` 파일을 참고하세요.**

[→ tasks.md 보기](./tasks.md)

### 현재 작업: 보스 체력 기반 클리어 가능 여부 판단 시스템

**목표**: ACT에서 보스 HP를 받아서 현재 파티 DPS로 엔레이지 전에 보스를 잡을 수 있는지 판단

**핵심 가치**: FFLogs 데이터 없어도 동작 (신규 레이드 첫날부터 사용 가능)

**작업 단계**:
1. Phase 1: 보스 HP 파싱 (백엔드)
2. Phase 2: 엔레이지 정보 제공자 (Cactbot 타임라인)
3. Phase 3: 클리어 가능 여부 계산
4. Phase 4: UI 표시

**데이터 소스**:
- 보스 HP: ACT AddCombatant (03 라인) - `p[10]` currentHp, `p[11]` maxHp
- 엔레이지 정보: Cactbot GitHub 타임라인 파일 (`ui/raidboss/data/`)
  - https://github.com/OverlayPlugin/cactbot

**참고**:
- Cactbot 타임라인 파일에서 `(\d+\.\d+)\s+".*\(enrage\)"` 패턴으로 엔레이지 시간 파싱
- Territory ID → Cactbot 파일 경로 매핑 필요 (Light-Heavyweight, Cruiserweight, Heavyweight)
- 신뢰도: 1분 미만 LOW, 1~3분 MEDIUM, 3분 이상 HIGH

---

## 아키텍처 원칙

### Hexagonal Architecture (Ports & Adapters)
```
core/               # 순수 비즈니스 로직 (Spring 무의존)
  ├── event/        # CombatEvent sealed interface
  ├── model/        # CombatState, ActorStats, ActorId 등
  ├── engine/       # CombatEngine (이벤트 → 상태 전환)
  ├── estimator/    # OnlineEstimator, PaceProfile
  └── snapshot/     # SnapshotAggregator, OverlaySnapshot

application/        # 서비스 계층 + 포트 인터페이스
  ├── CombatService.java
  ├── ActIngestionService.java
  └── port/
      ├── inbound/  # CombatEventPort
      └── outbound/ # SnapshotPublisher, PaceProfileProvider

adapter/            # 외부 시스템 연동
  ├── inbound/
  │   ├── actws/    # ActWsClient (ACT WebSocket)
  │   ├── tick/     # TickDriver (스냅샷 생성 주기)
  │   └── replay/   # ReplayController (JSONL 테스트용)
  └── outbound/
      ├── fflogsapi/    # FflogsApiClient (GraphQL)
      └── overlayws/    # MvcSnapshotPublisher (WebSocket)

config/             # Spring 빈 설정
```

**규칙:**
1. **core는 Spring 의존성 없음** - 순수 Java, record, sealed interface만 사용
2. **core는 외부를 모름** - adapter, application 패키지 import 금지
3. **adapter는 core/application만 의존** - adapter 간 직접 참조 금지
4. **포트는 application에 정의** - 인터페이스와 구현체 분리

---

## 코딩 규칙

### 1. 불변성 (Immutability)
```java
// GOOD: record 사용
public record ActorStats(
    ActorId actorId,
    String name,
    int jobId,
    long totalDamage,
    boolean isDead
) {}

// BAD: mutable 클래스
public class ActorStats {
    private long totalDamage; // setter 금지
}
```

### 2. Sealed Interface/Class
```java
// GOOD: 타입 안정성
public sealed interface CombatEvent permits
    AbilityDamage, BuffApply, BuffRemove, ActorDeath, Tick, ... {}

// BAD: instanceof 남발
if (event instanceof Object) { ... }
```

### 3. Null 안정성
```java
// GOOD: Optional 사용
public Optional<PaceProfile> loadProfile(ZoneId zoneId, EncounterId encounterId) {
    return Optional.ofNullable(cache.get(key));
}

// BAD: null 반환
public PaceProfile loadProfile(...) {
    return null; // NPE 위험
}
```

### 4. Value Object
```java
// GOOD: 타입 안정성
public record ActorId(String value) {
    public boolean isPet() {
        return value.startsWith("4");
    }
}

// BAD: primitive obsession
public void addDamage(String actorId, long damage) { ... }
```

### 5. 네이밍 규칙
- **Event**: `AbilityDamage`, `ActorDeath` (명사, 과거형 아님)
- **Port**: `~Port`, `~Provider`, `~Publisher` (인터페이스)
- **Adapter**: `ActWsClient`, `FflogsApiClient`, `MvcSnapshotPublisher`
- **Service**: `~Service` (application 계층만)
- **Engine/Aggregator**: 상태 변환 로직

---

## FFLogs API 사용 규칙

### 1. API 문서 필수 참고
모든 FFLogs 관련 작업 시 **반드시** 참고:
https://www.fflogs.com/v2-api-docs/ff/

### 2. GraphQL 쿼리 작성
```java
// GOOD: 필요한 필드만 요청
String query = """
    query {
      worldData {
        encounter(id: %d) {
          name
          id
        }
      }
    }
    """.formatted(encounterId);

// BAD: 불필요한 필드 과다 요청
```

### 3. 토큰 관리
```java
// GOOD: FflogsTokenStore에서 캐싱
@Component
public class FflogsTokenStore {
    private volatile String cachedToken;
    private volatile Instant expiresAt;
}

// BAD: 매번 토큰 재발급
```

### 4. API Key 관리
```properties
# application.properties에 직접 작성 (배포용)
pacemeter.fflogs.client-id=your-actual-client-id
pacemeter.fflogs.client-secret=your-actual-secret
```

**⚠️ 중요:**
- API key는 개발자가 발급받아 앱에 내장
- 사용자가 직접 입력하는 UI 절대 금지
- GitHub 공개 시 환경 변수로 전환

### 5. Partition (서버 리전)
```java
// 한국 서버: partition: "KR"
// 글로벌 서버: partition: null (기본값)

// characterRankings 호출 시 반드시 partition 명시
```

---

## 테스트 규칙

### 1. JSONL 리플레이 기반 테스트
```java
// GOOD: 실제 ACT 로그로 테스트
@Test
void replayActLog() {
    ReplayController.replay("src/test/resources/combat-log.jsonl");
}
```

### 2. 단위 테스트
```java
// GOOD: core 계층은 순수 함수 테스트
@Test
void testOnlineEstimator() {
    var estimator = new OnlineEstimator();
    var result = estimator.estimate(1000.0, 10.0);
    assertThat(result.rdps()).isCloseTo(100.0, within(0.1));
}
```

### 3. 테스트 파일 위치
```
src/test/resources/
  ├── combat-log.jsonl       # 실제 ACT 로그
  ├── party-wipe.jsonl       # 파티 전멸 시나리오
  └── late-start.jsonl       # 전투 중 프로그램 시작
```

---

## Git 커밋 규칙

### Conventional Commits
```
feat: 새 기능 추가
fix: 버그 수정
refactor: 리팩토링 (기능 변경 없음)
test: 테스트 코드 추가/수정
docs: 문서 수정
chore: 빌드 설정, 의존성 업데이트
```

### 예시
```
feat: FFLogs 개인 페이스 비교 구현

- FflogsApiClient에 sourceID 필터 추가
- ActorSnapshot에 individualPace 필드 추가
- SnapshotAggregator에서 currentPlayerId 기반 계산

Related: #42
```

---

## 금지 사항 (절대 하지 말 것)

### 1. core에 Spring 의존성 추가
```java
// BAD
package com.bohouse.pacemeter.core.engine;
import org.springframework.stereotype.Component; // 금지!

@Component // core는 순수 Java!
public class CombatEngine { ... }
```

### 2. 사용자에게 API key 입력 받기
```java
// BAD
@PostMapping("/api/config/fflogs")
public void setApiKey(@RequestBody String apiKey) { ... }
```

### 3. mutable 상태를 core에 만들기
```java
// BAD
public class ActorStats {
    private long totalDamage; // setter 금지

    public void addDamage(long damage) {
        this.totalDamage += damage; // 금지!
    }
}

// GOOD: 불변 객체 반환
public ActorStats addDamage(long damage) {
    return new ActorStats(..., totalDamage + damage, ...);
}
```

### 4. FFLogs API 문서 안 보고 추측으로 코딩
```java
// BAD: "아마도 이렇게 하면 되겠지?"
// GOOD: https://www.fflogs.com/v2-api-docs/ff/ 에서 확인 후 작성
```

### 5. adapter 간 직접 참조
```java
// BAD
package com.bohouse.pacemeter.adapter.inbound.actws;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient; // 금지!

// GOOD: application 계층의 포트를 통해서만 통신
```

---

## 주요 컴포넌트 설명

### CombatEngine
이벤트 기반 상태 머신. `CombatEvent` 수신 → `CombatState` 업데이트 → `EngineResult` 반환

```java
public EngineResult handleEvent(CombatEvent event, CombatState state) {
    return switch (event) {
        case AbilityDamage dmg -> handleDamage(dmg, state);
        case ActorDeath death -> handleDeath(death, state);
        case Tick tick -> handleTick(tick, state);
        // ...
    };
}
```

### SnapshotAggregator
`CombatState` → `OverlaySnapshot` 변환. UI에 표시할 데이터 구성.

```java
public OverlaySnapshot aggregate(CombatState state, PaceProfile partyProfile, PaceProfile individualProfile) {
    // rDPS 계산, 페이스 비교, 파티원 목록 구성
}
```

### PaceProfile
FFLogs TOP 데이터 기반 기준 페이스. 시간별 예상 누적 DPS.

```java
public interface PaceProfile {
    double expectedDamageAt(double elapsedSeconds);

    enum Type { NONE, STATIC, TIMELINE }
}
```

### ActWsClient
ACT OverlayPlugin WebSocket 클라이언트. 로그 수신 → 파싱 → 이벤트 발행.

```java
@Component
public class ActWsClient {
    public void connect(String url) { ... }

    // 재시도 로직: 최대 100회, 5초 대기
}
```

### FflogsApiClient
FFLogs GraphQL API 클라이언트. TOP 랭킹, 타임라인 조회.

```java
public List<TopRanking> fetchTopRankings(int zoneId, int encounterId, String jobName) { ... }
public List<TimelineEntry> fetchTimeline(int reportId, int fightId, int sourceId) { ... }
```

---

## 개발 워크플로우

### 1. 새 기능 추가 시
1. core에 이벤트/모델 정의 (순수 Java)
2. CombatEngine에 이벤트 핸들링 로직 추가
3. adapter에서 외부 데이터 파싱 → 이벤트 변환
4. SnapshotAggregator에서 UI 데이터 구성
5. 테스트 작성 (JSONL 리플레이)

### 2. FFLogs 관련 작업 시
1. API 문서 필수 확인: https://www.fflogs.com/v2-api-docs/ff/
2. GraphQL Playground에서 쿼리 테스트
3. FflogsApiClient에 메서드 추가
4. PaceProfile 구현체 작성/수정
5. 캐싱 전략 고려 (중복 호출 방지)

### 3. 배포 전 체크리스트
- [ ] application.properties에 실제 API key 설정
- [ ] 모든 테스트 통과 (`./gradlew test`)
- [ ] JSONL 리플레이 테스트 확인
- [ ] 실제 ACT 연결 테스트
- [ ] 프론트엔드 WebSocket 연결 확인

---

## 성능 최적화 원칙

### 1. 불필요한 객체 생성 최소화
```java
// GOOD: 변경 없으면 기존 객체 반환
if (damage == 0) return state;

// BAD: 매번 새 객체 생성
return state.withTotalDamage(state.totalDamage() + 0);
```

### 2. FFLogs API 캐싱
```java
// GOOD: 같은 전투는 한 번만 조회
private final Map<CacheKey, PaceProfile> cache = new ConcurrentHashMap<>();

// BAD: 매 틱마다 API 호출
```

### 3. WebSocket 메시지 최소화
```java
// GOOD: 변경 있을 때만 publish
if (snapshot.equals(lastSnapshot)) return;

// BAD: 매 틱마다 무조건 전송
```

---

## 디버깅 팁

### 1. ACT 연결 안 될 때
```bash
# ACT OverlayPlugin 확인
curl http://127.0.0.1:10501/
```

### 2. FFLogs API 오류
```java
// FflogsApiClient에 로깅 추가
log.info("GraphQL query: {}", query);
log.info("GraphQL response: {}", response);
```

### 3. JSONL 리플레이
```bash
# ReplayController로 실제 로그 재생
curl -X POST http://localhost:8080/replay?file=combat-log.jsonl
```

---

## 참고 문서

### 프로젝트 문서
- **작업 목록**: [tasks.md](./tasks.md) - 현재 진행 중인 작업과 할 일 목록
- **메모리**: `.claude/projects/.../memory/MEMORY.md` - 프로젝트 히스토리 및 패턴

### 외부 API/라이브러리
- **FFLogs API**: https://www.fflogs.com/v2-api-docs/ff/
- **ACT OverlayPlugin**: https://github.com/OverlayPlugin/OverlayPlugin
- **Cactbot (타임라인 소스)**: https://github.com/OverlayPlugin/cactbot
  - 타임라인 파일: `ui/raidboss/data/`
  - Zone ID: `resources/zone_id.ts`

### 아키텍처
- **Hexagonal Architecture**: https://alistair.cockburn.us/hexagonal-architecture/
