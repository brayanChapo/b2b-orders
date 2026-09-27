#!/usr/bin/env bash
set -euo pipefail

TOPIC="${1:-orders.processed.v1}"
TIMEOUT_MS="${2:-10000}"

cd "$(dirname "$0")/../.."

# Sin --group: usa un grupo temporal, así no interfiere con los offsets de los servicios.
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 \
  --topic "$TOPIC" \
  --from-beginning \
  --timeout-ms "$TIMEOUT_MS" \
  --property print.key=true \
  --property print.headers=true \
  --property print.timestamp=true || true
