#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${PACE_FFLOGS_CLIENT_ID:-}" || -z "${PACE_FFLOGS_CLIENT_SECRET:-}" ]]; then
  echo "[parity-repro] missing env: PACE_FFLOGS_CLIENT_ID / PACE_FFLOGS_CLIENT_SECRET" >&2
  exit 1
fi

echo "[parity-repro] running regression gate..."
./gradlew test \
  --tests com.bohouse.pacemeter.application.SubmissionParityRegressionGateTest

echo "[parity-repro] running parity diagnostics..."
./gradlew test \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugHeavy2Fight6Parity_withConfiguredFflogsCredentials_printsActorDelta \
  --tests com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors

RESULT_XML="build/test-results/test/TEST-com.bohouse.pacemeter.application.SubmissionParityReportDiagnostics.xml"
if [[ ! -f "$RESULT_XML" ]]; then
  echo "[parity-repro] missing diagnostics xml: $RESULT_XML" >&2
  exit 1
fi

echo "[parity-repro] summary from diagnostics:"
grep -n "rollup.summary" "$RESULT_XML" || true
grep -n "rollup.gate" "$RESULT_XML" || true
grep -n "rollup.submissions=" "$RESULT_XML" || true

echo "[parity-repro] selected fight sanity:"
grep -n "selectedFightId=2" "$RESULT_XML" | grep "2026-03-15-heavy4" || true
grep -n "selectedFightId=6" "$RESULT_XML" | grep "2026-03-18-heavy2" || true
grep -n "selectedFightId=8" "$RESULT_XML" | grep "2026-03-16-lindwurm" || true

echo "[parity-repro] done"
