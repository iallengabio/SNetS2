#!/usr/bin/env bash
# Runs the SNetS2 verification and validation campaign and regenerates figures and tables.
# Report: docs/review/04_relatorio_verificacao_validacao.md
#
# Usage: scripts/verification/run_campaign.sh [experiment ...]
#   experiments: erlang tx kr tandem algos energy phy qotnet (default: all)
# Python dependencies: pip install -r scripts/verification/requirements.txt
set -euo pipefail
cd "$(dirname "$0")/../.."

./build.sh
mvn -q test-compile
java -cp target/SNetS2-1.0-SNAPSHOT.jar:target/test-classes \
     com.snets2.verification.VerificationCampaign docs/review/vv/data "$@"
python3 scripts/verification/analyze.py docs/review/vv/data docs/review/vv
