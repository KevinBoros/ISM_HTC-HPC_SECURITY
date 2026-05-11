#!/bin/sh

ssh-keygen -A

mkdir -p /root/.ssh
cp /ssh-secrets/id_rsa /root/.ssh/id_rsa
cp /ssh-secrets/id_rsa.pub /root/.ssh/id_rsa.pub
cp /ssh-secrets/authorized_keys /root/.ssh/authorized_keys
cp /ssh-secrets/config /root/.ssh/config

chmod 700 /root/.ssh
chmod 600 /root/.ssh/id_rsa
chmod 600 /root/.ssh/authorized_keys
chmod 600 /root/.ssh/config
chmod 644 /root/.ssh/id_rsa.pub

/usr/sbin/sshd 

exec java -jar target/c3-consumer-1.0.jar