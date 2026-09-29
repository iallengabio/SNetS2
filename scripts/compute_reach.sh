#!/bin/bash
# Transparent reach (maxRange) of each modulation format of a setup.json, computed with the SNetS2
# physical layer model under the reference load of docs/formal_description/07_physical_layer_models.md, §6.2.
#
# Usage: scripts/compute_reach.sh <setup.json> [--bitRate worst|<Gbps>] [--step <km>]
#   --bitRate worst  (default) shortest reach over the bit rates of traffic.bitRates
#   --step 10        (default) reach rounded down to a multiple of this length (km)
# Prints a table (reach per bit rate, with slots per circuit and channels in the core) and the
# "modulations" JSON snippet with the computed maxRange.
set -e
cd "$(dirname "$0")/.."

if [ $# -lt 1 ]; then
    sed -n '2,9p' "$0"
    exit 2
fi

if command -v mvn &> /dev/null; then
    MVN_CMD="mvn"
elif command -v mise &> /dev/null; then
    MVN_CMD="mise exec -- mvn"
else
    echo "Erro: Apache Maven (mvn) não foi encontrado no PATH." >&2
    exit 1
fi

JAR=target/SNetS2-1.0-SNAPSHOT.jar
if [ ! -f "$JAR" ]; then
    $MVN_CMD -q package -DskipTests
fi
$MVN_CMD -q test-compile

java -cp "$JAR:target/test-classes" com.snets2.verification.ReachCalculator "$@"
