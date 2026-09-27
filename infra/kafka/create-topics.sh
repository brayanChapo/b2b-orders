set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:19092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
KT=/opt/kafka/bin/kafka-topics.sh

SEVEN_DAYS_MS=604800000
THIRTY_DAYS_MS=2592000000

create_topic() {
  topic="$1"
  retention_ms="$2"
  echo "→ ${topic} (particiones=${PARTITIONS}, retención=${retention_ms} ms)"
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor 1 \
    --config retention.ms="$retention_ms" \
    --config cleanup.policy=delete
}

# Entrada y salida: mismo número de particiones, key = orderId (ADR-001).
create_topic orders.created.v1      "$SEVEN_DAYS_MS"
create_topic orders.processed.v1    "$SEVEN_DAYS_MS"
# DLT: retención mayor para dar tiempo a investigar y reprocesar.
create_topic orders.processing.dlt  "$THIRTY_DAYS_MS"

echo ""
echo "Tópicos disponibles:"
"$KT" --bootstrap-server "$BOOTSTRAP" --describe \
  --topic 'orders\..*'
