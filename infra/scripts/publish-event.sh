#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Uso: $0 <archivo.json> [tópico] [repeticiones]" >&2
  exit 1
fi

FILE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
TOPIC="${2:-orders.created.v1}"
TIMES="${3:-1}"

if [[ ! -f "$FILE" ]]; then
  echo "No existe el archivo: $FILE" >&2
  exit 1
fi

# Key = orderId (sin depender de jq). Si no hay orderId, se publica con key "no-key".
KEY="$(grep -o '"orderId"[[:space:]]*:[[:space:]]*"[^"]*"' "$FILE" | head -n1 | sed 's/.*"\([^"]*\)"$/\1/' || true)"
KEY="${KEY:-no-key}"
# El productor de consola envía un mensaje por línea: se compacta el JSON a una línea.
PAYLOAD="$(tr -d '\n\r' < "$FILE")"

# docker compose debe ejecutarse desde la raíz del repositorio.
cd "$(dirname "$0")/../.."

for ((i = 1; i <= TIMES; i++)); do
  printf '%s|%s\n' "$KEY" "$PAYLOAD"
done | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:19092 \
  --topic "$TOPIC" \
  --property parse.key=true \
  --property key.separator='|'

echo "Publicado ${TIMES} vez/veces: $(basename "$FILE") → ${TOPIC} (key=${KEY})"
