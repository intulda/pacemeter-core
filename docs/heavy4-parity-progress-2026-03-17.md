# heavy4 parity 진행 메모

## 현재 상태

- 기준 submission: `2026-03-15-heavy4-vafpbaqjnhbk1mtw`
- FFLogs selected fight는 `fight 2`로 고정 검증됨
- heavy4 parity 현재 delta
  - 생쥐 / NIN: `+836.5`
  - 나성 / SGE: `-1782.9`
  - 치삐 / DRG: `-3756.7`
  - 후엔 / SCH: `-2227.5`
- `statusId=0` DoT 처리
  - SCH / NIN / SGE는 tracked recent application 기반 허용
  - DRG는 `405E` action 기반 가정 제거
  - tracked unknown-status DoT는 recent status apply 또는 recent application 중 하나가 있으면 허용
- heavy4 raw 검증 결과
  - SGE: `24 DoT=20`, `status apply(A38)=14`, `status snapshot(0A38)=244`
  - SCH: `24 DoT=3`, `status apply(767)=15`, `status snapshot(0767)=237`
  - DRG: `24 DoT=25`, `status apply(A9F)=15`, `status snapshot(0A9F)=214`
- selected fight 2 line 분포 검증 결과
  - parsed 주요 타입: `21`, `22`, `24`, `26`, `30`, 일부 `261`, 일부 `00`
  - 대량 미파싱 타입: `00`, `37`, `38`, `261`, `264`, `270`, `39`, `20`
- 한국어 `00` damage text 파서 보강 및 회귀 테스트 추가
  - 한글 combat text 파싱 테스트 통과
  - heavy4 damage text diagnostics:
    - `damageTextLines=5905`
    - `abilityLines=9851`
    - `exactAmount=9`
    - `exactAmountAndTarget=8`
    - `exactAmountTargetAndSource=4`
- 결론
  - 현재 남은 큰 delta의 주원인은 깨진 한글 damage text 파싱이 아님
  - 현재 핵심 이슈는 `high_unknown_skill_ratio`와 hidden attribution / 미해석 line type 가능성

## 다음 진행사항

1. NIN / SGE / DRG의 `high_unknown_skill_ratio`를 actor별, skill-id별로 다시 분해
2. selected fight 2에서 unknown skill로 집계되는 raw line 주변의 `20 / 37 / 38 / 39 / 264 / 261` 패턴 대조
3. FFLogs top skill breakdown과 local unknown bucket을 1:1로 비교해서 후보 스킬 식별
4. 실제 근거가 확인된 경우에만 parser 또는 ingestion에 신규 line type 해석 추가
5. 변경 후 heavy4 parity를 다시 측정해서 delta 감소 여부 재검증

## 검증 메모

- `ActLineParserTest` 통과
- `SubmissionParityReportServiceTest.debugHeavy4DamageTextDiagnostics_printsMatchCounts` 통과
- `SubmissionParityReportServiceTest.debugHeavy4Fight2LineTypeDistribution_printsUnparsedCandidates` 통과
- `SubmissionParityReportServiceTest.debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta` 통과
