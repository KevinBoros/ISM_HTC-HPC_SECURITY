@echo off
setlocal

echo Preparing SSH secrets...

if not exist secrets\mpi mkdir secrets\mpi

if not exist secrets\mpi\id_rsa (
    ssh-keygen -t rsa -b 4096 -f secrets\mpi\id_rsa -N ""
)

if not exist secrets\mpi\id_rsa.pub (
    ssh-keygen -y -f secrets\mpi\id_rsa > secrets\mpi\id_rsa.pub
)

copy /Y secrets\mpi\id_rsa.pub secrets\mpi\authorized_keys >nul

(
echo Host *
echo     StrictHostKeyChecking no
echo     UserKnownHostsFile /dev/null
echo     LogLevel ERROR
) > secrets\mpi\config

echo Cleaning old containers...
docker compose down --remove-orphans

echo Removing leftover ismclean containers...
for /f "tokens=*" %%i in ('docker ps -aq --filter "name=ismclean"') do docker rm -f %%i

echo Building and starting detached...
docker compose up --build -d

echo Status:
docker compose ps -a

pause