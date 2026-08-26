#!/bin/bash
set -e

BOOTSTRAP=localhost:9092
CONTAINER=ticketing-kafka

echo "Waiting for Kafka to be ready..."
until docker exec $CONTAINER /opt/kafka/bin/kafka-topics.sh \
  --list --bootstrap-server $BOOTSTRAP > /dev/null 2>&1; do
  echo "Kafka not ready yet, retrying in 3s..."
  sleep 3
done

echo "Kafka ready. Creating topics..."

TOPICS=(
  "seat-events:3"
  "payment-events:3"
  "booking-events:3"
  "notification-events:3"
)

for TOPIC_SPEC in "${TOPICS[@]}"; do
  TOPIC="${TOPIC_SPEC%%:*}"
  PARTITIONS="${TOPIC_SPEC##*:}"

  docker exec $CONTAINER /opt/kafka/bin/kafka-topics.sh \
    --create \
    --if-not-exists \
    --topic "$TOPIC" \
    --bootstrap-server $BOOTSTRAP \
    --partitions $PARTITIONS \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --config retention.bytes=1073741824

  echo "Topic created (or already exists): $TOPIC ($PARTITIONS partitions)"
done

echo "All topics ready."