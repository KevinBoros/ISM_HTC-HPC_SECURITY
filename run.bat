@echo off

docker compose down --remove-orphans
docker compose up --build -d
docker compose ps

pause