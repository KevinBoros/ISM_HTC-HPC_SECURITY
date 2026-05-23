#!/bin/sh

echo "Preparing SSH secrets..."

mkdir -p secrets/mpi

if [ ! -f secrets/mpi/id_rsa ]; then
    ssh-keygen -t rsa -b 4096 -f secrets/mpi/id_rsa -N ""
fi

if [ ! -f secrets/mpi/id_rsa.pub ]; then
    ssh-keygen -y -f secrets/mpi/id_rsa > secrets/mpi/id_rsa.pub
fi

cp secrets/mpi/id_rsa.pub secrets/mpi/authorized_keys

cat > secrets/mpi/config <<EOF
Host *
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    LogLevel ERROR
EOF

echo "Cleaning old containers..."
docker compose down --remove-orphans

echo "Removing leftover ismclean containers..."
LEFTOVERS=$(docker ps -aq --filter "name=ismclean")
if [ -n "$LEFTOVERS" ]; then
    docker rm -f $LEFTOVERS
fi

echo "Removing containers using host port 8080..."
PORT_8080_CONTAINERS=$(docker ps -aq --filter "publish=8080")
if [ -n "$PORT_8080_CONTAINERS" ]; then
    docker rm -f $PORT_8080_CONTAINERS
fi

echo "Building and starting detached..."
docker compose up --build -d

echo "Status:"
docker compose ps -a