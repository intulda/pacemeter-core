# Parity Cross-Env Runbook

## 목적
- 맥/윈도우(WSL 포함) 어디서 실행해도 parity 기준선을 동일하게 검증하기 위한 고정 절차.

## 고정 조건
- git 기준 브랜치/커밋이 동일해야 한다.
- `data/submissions/*` 파일이 동일해야 한다.
- FFLogs credential 환경변수가 동일해야 한다.

필수 환경변수:
- `PACE_FFLOGS_CLIENT_ID`
- `PACE_FFLOGS_CLIENT_SECRET`

## 1) 실행
프로젝트 루트에서:

```bash
./scripts/parity_repro_check.sh
```

## 2) 기대값 (현재 기준선)
- rollup gate: `pass=true`
- submission fight id:
  - heavy4: `selectedFightId=2`
  - heavy2: `selectedFightId=6`
  - lindwurm: `selectedFightId=8`

rollup 근사 기준:
- `mape ~= 0.011 ~ 0.013`
- `p95 ~= 0.028 ~ 0.032`
- `max ~= 0.034 ~ 0.040`

## 3) 불일치 시 점검 순서
1. `git status --short`가 깨끗한지 확인 (`.DS_Store` 제외).
2. `data/submissions/*/metadata.json`의 `fflogsFightId`가 변경되지 않았는지 확인.
3. `src/main/java/com/bohouse/pacemeter/application/ActIngestionService.java`에 실험 분기가 재유입됐는지 확인.
4. `SubmissionParityReportDiagnostics`에서 heavy2 selected fight assertion이 `6`인지 확인.
5. 다시 `./scripts/parity_repro_check.sh` 실행.

## 4) 실험 원칙
- 한 번에 1개 가설만 변경.
- 변경 후 즉시 `parity_repro_check.sh` 실행.
- heavy2 개선을 위해 heavy4/lindwurm를 악화시키면 롤백.
