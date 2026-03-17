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

## Docker 배포

```bash
# 이미지 빌드
docker build -t pacemeter-backend .

# 운영 프로필로 실행
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e PACE_FFLOGS_CLIENT_ID=your-client-id \
  -e PACE_FFLOGS_CLIENT_SECRET=your-client-secret \
  -e PACE_FFLOGS_PARTITION= \
  -e PACE_ACT_DIRECT_ENABLED=false \
  pacemeter-backend
```

### 주요 환경변수

- `SPRING_PROFILES_ACTIVE`: `local`, `dev`, `prod` 중 선택. Docker 기본값은 `prod`
- `PACE_FFLOGS_CLIENT_ID`: FFLogs API client id
- `PACE_FFLOGS_CLIENT_SECRET`: FFLogs API client secret
- `PACE_FFLOGS_PARTITION`: 한국 서버면 `KR`, 글로벌이면 빈 값
- `PACE_ACT_DIRECT_ENABLED`: 서버가 직접 ACT WebSocket에 붙을지 여부. 중앙 서버 배포는 보통 `false`
- `JAVA_OPTS`: JVM 옵션 추가 메모리 제한 예시 `-Xms256m -Xmx512m`

현재 구조는 relay 세션과 overlay 연결 상태를 인메모리로 관리하므로, 운영 배포는 단일 인스턴스를 권장한다.

## Docker Compose 운영

```bash
cp .env.prod.example .env.prod
docker compose --env-file .env.prod up -d nginx pacemeter-blue
```

- 외부 진입점: `nginx`
- 내부 앱 컨테이너: `pacemeter-blue`, `pacemeter-green`
- 로컬 점검 포트:
  - blue: `18081`
  - green: `18082`

## 무중단 배포

blue/green 방식으로 배포한다.

```bash
chmod +x scripts/deploy_blue_green.sh
docker compose --env-file .env.prod up -d nginx pacemeter-blue
./scripts/deploy_blue_green.sh
```

배포 스크립트가 하는 일:
- 현재 active 색상 확인
- 반대 색상 컨테이너를 `--build`로 기동
- 새 컨테이너의 `/ready`가 `200 OK`인지 확인
- nginx upstream을 새 색상으로 전환 후 reload
- 드레인 시간 이후 기존 색상 컨테이너 정지

주의:
- readiness 판정은 `GET /ready` 기준이다.
- WebSocket 트래픽은 nginx가 그대로 프록시한다.
- 현재 애플리케이션 상태는 인메모리이므로 다중 인스턴스 확장보다는 단일 활성 인스턴스 운영을 전제로 한다.
- 기본 드레인 시간은 `30초`이며, `DRAIN_SECONDS=60 ./scripts/deploy_blue_green.sh`처럼 조정할 수 있다.

## FFLogs Parity 로그 수집

FFLogs Live rDPS와 paceMeter 추정값을 비교하려면 실제 전투 로그 수집이 필요하다.

- 수집 가이드: [docs/fflogs-parity-log-collection.md](/Users/kimbogeun/Documents/Project/paceMeter/docs/fflogs-parity-log-collection.md)
- 메타데이터 템플릿: [docs/metadata-template.json](/Users/kimbogeun/Documents/Project/paceMeter/docs/metadata-template.json)
- 익명화 스크립트: [scripts/anonymize_act_log.py](/Users/kimbogeun/Documents/Project/paceMeter/scripts/anonymize_act_log.py)
- SQLite 등록 스크립트: [scripts/register_log_submission.py](/Users/kimbogeun/Documents/Project/paceMeter/scripts/register_log_submission.py)
- SQLite 조회 스크립트: [scripts/list_log_submissions.py](/Users/kimbogeun/Documents/Project/paceMeter/scripts/list_log_submissions.py)

예시:

```bash
python3 scripts/anonymize_act_log.py raw.log shared.log --mapping-output mapping.json
python3 scripts/register_log_submission.py data/submissions/sample-01
```

등록된 submission의 로컬 parity 초안 리포트는 아래 엔드포인트로 조회할 수 있다.

```bash
GET /api/debug/parity/submissions/{submissionId}
```

## 개발 현황

### 완성 (백엔드 코어)
- 코어 전투 엔진 (상태 머신, 이벤트 리덕션)
- DPS 추정 (가중 평균 + 신뢰도 점수)
- 스냅샷 생성 파이프라인 (partyPace + individualPace)
- ACT 로그 파서 (한국어 클라이언트)
- 파티원 전체 데미지 수집 (8명 전체)
- 사망 이벤트 처리 (ActorDeath, isDead)
- WebSocket 서버 (MvcSnapshotPublisher)
- FF Logs API 연동 (TOP 페이스 비교)
- ACT 연결 안정성 (재시도 로직, 타임아웃)
- JSONL 리플레이 기반 테스트

### 진행 예정
- UI 완성도 개선 (직업 아이콘, 순위 표시)
- 히스토리 기능 (전투 기록 저장)
- 버프 기여도 기반 rDPS 보정 (장기)

## 라이선스

MIT
