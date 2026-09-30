#!/usr/bin/env bash
# Builds the harness if needed and runs the experiment matrix; every argument is passed to the runner.
#   ./run-matrix.sh                                   # full matrix E1..E10, 20 runs each
#   ./run-matrix.sh --exp=E4,E6 --runs=5 --parallel=4
#   ./run-matrix.sh --exp=custom --proxy=on --lingering=always --heartbeat=forced --ping-phase=P2 --sink-rate=20000
#   ./run-matrix.sh --summarize                       # rebuild results.csv / summary.md only
# Packet capture needs tcpdump and permission to capture on lo (root or CAP_NET_RAW); pass --tcpdump=off to skip.
# Set REBUILD=1 to force a Maven build.
set -euo pipefail
cd "$(dirname "$0")"
if [[ -n "${REBUILD:-}" || ! -f server/target/server.jar || ! -f client/target/client.jar || ! -f runner/target/runner.jar ]]; then
  mvn -q -B package
fi
exec java -jar runner/target/runner.jar "$@"
