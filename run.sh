#!/bin/sh

echo "Cleaning old containers..."
docker compose down --remove-orphans

echo "Building and starting..."
docker compose up --build -d

echo "Status:"
docker compose ps