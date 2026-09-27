set -euo pipefail

DB="${MONGO_DB:-orders}"
cd "$(dirname "$0")/../.."

if [[ $# -eq 0 ]]; then
  docker compose exec mongo mongosh "mongodb://mongo:27017/${DB}?replicaSet=rs0"
else
  docker compose exec -T mongo mongosh "mongodb://mongo:27017/${DB}?replicaSet=rs0" --quiet --eval "$1"
fi
