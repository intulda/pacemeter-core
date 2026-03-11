# paceMeter 작업 목록

## 🎯 현재 작업: 보스 체력 기반 클리어 가능 여부 판단 시스템

**목표**: ACT에서 보스 HP 정보를 받아서, 현재 파티 DPS로 엔레이지 전에 보스를 잡을 수 있는지 판단

**핵심 가치**: FFLogs 데이터 없어도 동작 (신규 레이드 첫날부터 사용 가능)

---

## Phase 1: 보스 HP 파싱 (백엔드)

### ✅ 완료
- 없음

### 🔲 진행 중
- 없음

### 📋 할 일

- [ ] **CombatantAdded record 수정**
  - `currentHp`, `maxHp` 필드 추가
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/inbound/actws/CombatantAdded.java`

- [ ] **ActLineParser 수정**
  - AddCombatant (03 라인) 파싱 시 `p[10]` (currentHp), `p[11]` (maxHp) 읽기
  - `parseDecimalLong()` 메서드 추가
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/inbound/actws/ActLineParser.java:87-93`

- [ ] **CombatEvent에 BossIdentified 이벤트 추가**
  - `record BossIdentified(ActorId, String name, long maxHp, Instant)`
  - 파일: `src/main/java/com/bohouse/pacemeter/core/event/CombatEvent.java`

- [ ] **CombatState에 보스 정보 필드 추가**
  - `Optional<BossInfo>` 필드
  - `record BossInfo(ActorId, String name, long maxHp)`
  - `withBoss()` 메서드
  - 파일: `src/main/java/com/bohouse/pacemeter/core/model/CombatState.java`

- [ ] **CombatEngine에서 BossIdentified 이벤트 처리**
  - `handleBossIdentified()` 메서드 추가
  - 파일: `src/main/java/com/bohouse/pacemeter/core/engine/CombatEngine.java`

- [ ] **ActIngestionService에서 보스 식별 로직**
  - `onCombatantAdded()` 메서드에 보스 판단 로직 추가
  - `isBoss()` 메서드: NPC이고, HP 50M 이상, 펫 아님
  - 파일: `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`

- [ ] **테스트 작성**
  - ActLineParser 단위 테스트 (HP 파싱)
  - 보스 식별 로직 테스트
  - JSONL 리플레이 테스트

**예상 시간**: 2~3시간

---

## Phase 2: 엔레이지 정보 제공자 (백엔드)

### 📋 할 일

- [ ] **EnrageTimeProvider 인터페이스 정의**
  - `Optional<EnrageInfo> getEnrageTime(int territoryId)`
  - `record EnrageInfo(double seconds, Confidence, String source)`
  - 파일: `src/main/java/com/bohouse/pacemeter/application/port/outbound/EnrageTimeProvider.java`

- [ ] **CactbotFileMapping 구현**
  - Territory ID → Cactbot 파일 경로 매핑 테이블
  - Light-Heavyweight (M1S~M4S), Cruiserweight (M5S~M8S), Heavyweight (M9S~M12S)
  - Endwalker P1S~P12S, Ultimate 추가
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/outbound/cactbot/CactbotFileMapping.java`

- [ ] **CactbotTimelineProvider 구현**
  - GitHub Raw URL로 타임라인 다운로드
  - 정규식으로 "Enrage" 라인 파싱: `(\d+\.\d+)\s+".*\(enrage\)"`
  - 메모리 캐싱 (ConcurrentHashMap)
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/outbound/cactbot/CactbotTimelineProvider.java`

- [ ] **로컬 캐싱 (선택사항)**
  - `~/.pacemeter/cactbot-cache/` 디렉토리에 파일 저장
  - 오프라인 대응
  - 파일: `src/main/java/com/bohouse/pacemeter/adapter/outbound/cactbot/LocalCactbotCache.java`

- [ ] **Spring 빈 설정**
  - RestTemplate 빈 추가 (없으면)
  - EnrageTimeProvider 빈 등록
  - 파일: `src/main/java/com/bohouse/pacemeter/config/AppWiringConfig.java`

- [ ] **테스트 작성**
  - CactbotFileMapping 테스트 (모든 Territory ID 매핑 확인)
  - CactbotTimelineProvider 통합 테스트 (실제 GitHub 다운로드)
  - Enrage 파싱 단위 테스트

**예상 시간**: 3~4시간

---

## Phase 3: 클리어 가능 여부 계산 (백엔드)

### 📋 할 일

- [ ] **ClearabilityCheck record 정의**
  - `boolean canClear`, `double estimatedKillTime`, `double enrageTime`, `double margin`
  - `double requiredDps`, `Confidence confidence`
  - `static ClearabilityCheck calculate(...)` 메서드
  - 파일: `src/main/java/com/bohouse/pacemeter/core/snapshot/ClearabilityCheck.java`

- [ ] **SnapshotAggregator 수정**
  - `aggregate()` 메서드에 `Optional<EnrageInfo>` 파라미터 추가
  - 보스 정보 + 엔레이지 정보 있으면 ClearabilityCheck 계산
  - 신뢰도 계산 (경과 시간 기반)
  - 파일: `src/main/java/com/bohouse/pacemeter/core/snapshot/SnapshotAggregator.java`

- [ ] **OverlaySnapshot에 clearability 필드 추가**
  - `Optional<ClearabilityCheck> clearability`
  - 파일: `src/main/java/com/bohouse/pacemeter/core/snapshot/OverlaySnapshot.java`

- [ ] **CombatService 수정**
  - EnrageTimeProvider 주입
  - `onTick()` 메서드에서 엔레이지 정보 조회
  - SnapshotAggregator에 전달
  - 파일: `src/main/java/com/bohouse/pacemeter/application/CombatService.java`

- [ ] **계산 로직 검증**
  - 보스 MaxHP / 파티 평균 DPS = 예상 킬타임
  - 예상 킬타임 < 엔레이지 → 클리어 가능
  - 필요 DPS = 보스 MaxHP / 엔레이지

- [ ] **테스트 작성**
  - ClearabilityCheck.calculate() 단위 테스트
  - SnapshotAggregator 통합 테스트
  - JSONL 리플레이로 실제 시나리오 테스트

**예상 시간**: 2~3시간

---

## Phase 4: UI 표시 (프론트엔드)

### 📋 할 일

- [ ] **TypeScript 타입 정의**
  - `ClearabilityCheck` interface
  - `Confidence` enum
  - 파일: `overlay/src/types/overlay.ts`

- [ ] **ClearabilityIndicator 컴포넌트 생성**
  - 클리어 가능/불가능 표시
  - 예상 킬타임 vs 엔레이지
  - 여유 시간 / 부족 시간
  - 필요 DPS (부족할 때)
  - 신뢰도 표시 (LOW일 때 경고)
  - 파일: `overlay/src/components/ClearabilityIndicator.tsx`

- [ ] **HUD에 통합**
  - Main HUD 또는 별도 패널
  - snapshot.clearability 데이터 바인딩
  - 파일: `overlay/src/components/HUD.tsx`

- [ ] **스타일링**
  - 클리어 가능: 초록색 (✅)
  - 클리어 불가: 빨간색 (❌)
  - 신뢰도 낮음: 노란색 (⚠️)
  - 빠듯함: 주황색 (⚠️)

- [ ] **테스트**
  - 각 상태별 UI 확인
  - 실제 전투에서 동작 확인
  - Edge case 테스트 (데이터 없음, 보스 없음 등)

**예상 시간**: 2~3시간

---

## 🔮 향후 추가 기능 (우선순위 낮음)

### 백엔드 개선
- [ ] 서버(리전) 선택 기능 (한국/글로벌/일본)
- [ ] 설정 API 엔드포인트 추가 (런타임 설정 변경)
- [ ] Encounter 자동 매칭 개선 (targetName → encounter 매핑)
- [ ] FFLogs API 캐싱 및 성능 최적화
- [ ] 에러 핸들링 강화 (rate limit, 네트워크 오류)
- [ ] 로깅 개선 (전투 종료 시 요약 로그)
- [ ] 히스토리 저장 기능 (SQLite/H2)
- [ ] 통계 API (`GET /api/stats`)
- [ ] 관리 엔드포인트 (`POST /api/combat/reset` 등)

### 프론트엔드 개선
- [ ] 직업 아이콘 추가 (`/public/icons/jobs/`)
- [ ] 성능 최적화 (useMemo, 불필요한 리렌더링 방지)
- [ ] 설정 UI (FFLogs API key, 서버 선택 - 필요 시)
- [ ] 전투 종료 시 최종 결과 화면
- [ ] 에러 표시 (WebSocket 연결 실패, API 오류)
- [ ] 히스토리 뷰 (과거 전투 기록)
- [ ] 추가 정보 표시 (신뢰도 점수, 예상 킬타임 그래프)

---

## 📌 참고 문서

- **Cactbot GitHub**: https://github.com/OverlayPlugin/cactbot
- **Cactbot 타임라인 파일**: `ui/raidboss/data/`
- **FFLogs API**: https://www.fflogs.com/v2-api-docs/ff/
- **ACT OverlayPlugin**: https://github.com/OverlayPlugin/OverlayPlugin
- **프로젝트 MEMORY**: `.claude/projects/.../memory/MEMORY.md`
- **개발 가이드**: `CLAUDE.md`

---

## 📝 작업 진행 시 주의사항

1. **각 Phase 완료 후 테스트 필수**
   - 단위 테스트 작성
   - JSONL 리플레이 테스트
   - 실제 ACT 연결 테스트

2. **CLAUDE.md 개발 규칙 준수**
   - Hexagonal Architecture 유지
   - core는 Spring 의존성 금지
   - 불변성 (record 사용)
   - 테스트 코드 작성

3. **커밋 규칙**
   - Phase별로 커밋
   - Conventional Commits 형식
   - `feat:`, `fix:`, `refactor:` 등

4. **에러 처리**
   - Cactbot 다운로드 실패 시 graceful degradation
   - 보스 정보 없을 때 클리어 가능 여부 표시 안 함
   - 엔레이지 정보 없을 때 "데이터 없음" 표시

5. **성능 고려**
   - Cactbot 타임라인 캐싱 (메모리 + 로컬 파일)
   - 불필요한 API 호출 최소화
   - UI 리렌더링 최적화

---

## 🎯 완료 기준

- [x] 모든 Phase 완료
- [x] 모든 테스트 통과
- [x] 실제 ACT 연결 테스트 성공
- [x] 프론트엔드 UI 동작 확인
- [x] Edge case 처리 확인
- [x] CLAUDE.md 문서 업데이트

---

**최종 목표**: FFLogs 데이터 없어도 "이 딜로 가면 전멸기 안 보고 잡을 수 있나?"를 알 수 있게 하기!
