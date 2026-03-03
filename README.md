# paceMeter

FFXIV 실시간 전투 페이스 비교 오버레이. ACT(Advanced Combat Tracker)에서 전투 데이터를 수신하여 FF Logs 기준 데이터와 비교, 현재 파티의 DPS 페이스를 실시간으로 표시한다.

## 주요 기능

- ACT WebSocket 연동으로 실시간 전투 로그 수집
- 파티 전체 DPS 및 캐릭터별 개인 DPS 계산
- 15초 슬라이딩 윈도우 기반 최근 DPS + 누적 DPS 가중 평균 rDPS 추정
- 기준 페이스 대비 실제 누적 데미지 비교 (예상 킬타임 포함)
- 신뢰도 점수 (전투 시간, 샘플 수, 분산 기반 페널티)

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어 | Java 17 |
| 프레임워크 | Spring Boot 4.0.2 |
| 빌드 | Gradle 9.3.0 |
| 아키텍처 | Hexagonal (Ports & Adapters) |
| 테스트 | JUnit 5 + JSONL 리플레이 |

## 프로젝트 구조

```
src/main/java/com/bohouse/pacemeter/
├── core/               # 순수 비즈니스 로직 (Spring 무의존)
│   ├── event/          # CombatEvent sealed interface
│   ├── model/          # CombatState, ActorStats 등
│   ├── engine/         # CombatEngine
│   ├── snapshot/       # OverlaySnapshot, SnapshotAggregator
│   └── estimator/      # OnlineEstimator, Confidence
├── application/        # 서비스 계층 + 포트 인터페이스
├── adapter/            # ACT WebSocket, 틱 스케줄러
└── config/             # Spring 빈 설정
```

## 빌드 & 실행

```bash
# 빌드
./gradlew build

# 테스트
./gradlew test

# 실행
./gradlew bootRun
```

ACT OverlayPlugin WebSocket이 `ws://127.0.0.1:10501/ws`에서 실행 중이어야 한다.

## 개발 현황

### 완성
- 코어 전투 엔진 (상태 머신, 이벤트 리덕션)
- DPS 추정 (가중 평균 + 신뢰도 점수)
- 스냅샷 생성 파이프라인
- ACT 로그 파서 (한국어 클라이언트)
- JSONL 리플레이 기반 테스트

### 진행 예정
- 파티원 전체 데미지 수집 (현재 본인만)
- WebSocket 출력 어댑터 (Electron 클라이언트용)
- 페이스 프로파일 JSON 로딩
- Electron + React 오버레이 UI
- 버프 기여도 기반 rDPS 보정
- FF Logs API 연동

## 라이선스

MIT