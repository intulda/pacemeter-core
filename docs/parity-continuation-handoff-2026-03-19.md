# Parity Continuation Handoff (2026-03-19)

## 1) 작업 방식
- 같은 기준 명령으로 heavy4, heavy2, Lindwurm, rollup을 반복 측정한다.
- 한 번에 한 가지만 바꾸고 수치 변화를 바로 기록한다.
- line type, GUID, event 증거가 없는 추측성 보정은 넣지 않는다.

## 2) 지금 기준에서 꼭 기억할 점
- heavy4는 `fight=2`가 맞다.
- heavy2는 기존 문서와 metadata에 적혀 있던 `fight=6`이 잘못이었고, 실제로는 `fight=2`가 맞다.
- `fight=6`은 Lindwurm 구간이라 heavy2 raw와 절대 시간이 맞지 않는다.

## 3) 이번에 새로 확인된 사실
- heavy2 raw의 `WHM Dia` 24라인은 `fight=6` 기준에서 `included24=0`이었다.
- 즉 heavy2 잔차를 action/status 정규화만으로 보기 전에, 먼저 report fight 선택을 바로잡아야 했다.
- `tasks.md`의 메모인 `heavy2 fightId=2 유지`가 맞았다.

## 4) 이미 반영된 주요 수정
- replay profile 재발행 문제 수정.
- owner/pet self-buff guard 추가.
- `Starry Muse(3685)`를 `5%`로 수정.
- unknown-status DoT attribution에 `21 application + 37 signal` 교차 확인 추가.

## 5) 다음 작업
1. heavy2를 `fight=2` 기준으로 다시 측정한다.
2. 그 결과로 `WHM/SCH/SAM` 잔차를 다시 정리한다.
3. 필요한 경우 `Dia`, `Biolysis`, `Baneful Impaction`, `Higanbana`의 action/status normalization을 넣는다.
4. Lindwurm은 현재 수치가 유지되는지만 회귀 확인한다.

## 6) 참고 파일
- 진행 로그: `docs/parity-patch-notes.md`
- 이전 handoff: `docs/home-test-handoff-2026-03-19.md`
