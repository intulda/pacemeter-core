# FFLogs Parity 로그 수집 가이드

## 목적

paceMeter의 실시간 rDPS 추정값을 FFLogs Live rDPS와 비교하기 위해, 실제 전투 로그와 대응 메타데이터를 수집한다.

이 수집은 아래 문제를 분리해서 확인하기 위한 것이다.

- 버프/디버프 ID 매칭 누락
- DoT snapshot 처리 누락
- crit/direct hit 기여 분배 오차
- 한국어/영문 클라이언트 로그 차이
- 특정 직업 조합에서만 발생하는 편차

## 왜 이 방식이 좋은가

- 계산식 논쟁보다 실제 오차를 먼저 볼 수 있다.
- 로그와 FFLogs 리포트를 같이 받으면 “파싱 문제”와 “수식 문제”를 분리할 수 있다.
- 익명화 기준을 먼저 정하면 사용자 협조를 받기 쉽다.
- 수집 포맷을 고정해두면 나중에 자동 비교 리포트를 만들기 쉬워진다.

## 수집 단위

한 건의 제출은 아래 세 파일을 기준으로 한다.

1. ACT 원본 로그 또는 pull 단위 추출 로그
2. 메타데이터 JSON
3. 가능하면 FFLogs 리포트 URL

## 제출 파일 구조

```text
data/submissions/
  submission-<date>-<encounter>-<alias>/
    combat.log
    metadata.json
    mapping.json
    notes.txt
```

- `combat.log`: ACT 네트워크 로그 원본 또는 해당 풀만 잘라낸 파일
- `metadata.json`: 필수 메타데이터
- `mapping.json`: 익명화 전 원래 이름 매핑. 외부 공유 금지
- `notes.txt`: 선택 사항. 특이사항 기록

## metadata.json 필드

예시는 [metadata-template.json](/Users/kimbogeun/Documents/Project/paceMeter/docs/metadata-template.json)을 따른다.

- `submissionId`: 제출 식별자
- `submittedAt`: ISO-8601 제출 시각
- `region`: `KR`, `NA`, `EU`, `JP` 중 하나
- `clientLanguage`: `ko`, `en`, `ja`, `de`, `fr` 중 하나
- `zoneId`: ACT territory id
- `encounterName`: 전투 이름
- `difficulty`: `normal`, `extreme`, `savage`, `ultimate`, `criterion` 등
- `partyJobs`: 파티 직업 목록
- `fflogsReportUrl`: 대응 FFLogs 리포트 URL
- `fflogsFightId`: 가능하면 fight id
- `pullStartApprox`: 풀 시작 대략 시각 또는 설명
- `hasDotTicks`: `24` 로그 포함 여부
- `notes`: 특이사항

## 최소 수집 기준

아래 중 가능한 많은 조건을 만족하는 로그를 우선 수집한다.

- FFLogs 리포트가 있는 로그
- 8인 파티 로그
- 한 풀 전체가 포함된 로그
- 버프 직업이 다양한 조합
- DoT 직업이 포함된 조합
- 와이프와 클리어 로그 둘 다 존재

## 익명화 원칙

- 캐릭터명은 가명으로 치환한다.
- 파티명, 링크셸명, 자유부대명처럼 식별 가능 정보는 제거한다.
- FFLogs 리포트 URL은 필요 시 유지하되, 별도 공개 저장소에는 올리지 않는다.

익명화는 [anonymize_act_log.py](/Users/kimbogeun/Documents/Project/paceMeter/scripts/anonymize_act_log.py)로 처리한다.

제출 카탈로그 등록은 [register_log_submission.py](/Users/kimbogeun/Documents/Project/paceMeter/scripts/register_log_submission.py)로 처리한다.

## 권장 워크플로우

1. 원본 로그를 별도 보관한다.
2. 익명화 스크립트로 제출용 로그를 만든다.
3. `metadata.json`을 채운다.
4. FFLogs 리포트 URL과 pull 정보를 붙인다.
5. `data/submissions/<submissionId>/` 아래에 저장한다.
6. SQLite 카탈로그에 등록한다.

예시:

```bash
python3 scripts/anonymize_act_log.py raw.log data/submissions/sample-01/combat.log \
  --mapping-output data/submissions/sample-01/mapping.json

cp docs/metadata-template.json data/submissions/sample-01/metadata.json

python3 scripts/register_log_submission.py data/submissions/sample-01
python3 scripts/list_log_submissions.py
```

## 향후 자동화 계획

- 로그와 FFLogs 비교 리포트 생성 스크립트
- 누락 버프/디버프 자동 후보 추출
