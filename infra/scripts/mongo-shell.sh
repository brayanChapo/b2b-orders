#!/usr/bin/env bash
set -euo pipefail

# Git Bash (Windows) convierte argumentos como /opt/... en rutas de Windows antes de pasarlos a Docker.
export MSYS_NO_PATHCONV=1

DB="${MONGO_DB:-orders}"
cd "$(dirname "$0")/../.."

if [[ $# -eq 0 ]]; then
  docker compose exec mongo mongosh "mongodb://mongo:27017/${DB}?replicaSet=rs0"
else
  docker compose exec -T mongo mongosh "mongodb://mongo:27017/${DB}?replicaSet=rs0" --quiet --eval "$1"
fi
