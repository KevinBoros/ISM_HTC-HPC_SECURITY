FROM ubuntu:24.04

RUN ln -sf /usr/bin/mpirun /usr/local/bin/mpirun || true \
    && ln -sf /usr/bin/mpicc /usr/local/bin/mpicc || true \
    && ln -sf /usr/bin/prted /usr/local/bin/prted || true \
    && ln -sf /usr/bin/prte /usr/local/bin/prte || true \
    && ln -sf /usr/bin/orted /usr/local/bin/orted || true


RUN apt-get update && apt-get install -y \
    build-essential \
    openmpi-bin \
    libopenmpi-dev \
    openssh-server \
    openssh-client \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/mpi-app

COPY mpi/bmp_mpi_worker.c /opt/mpi-app/bmp_mpi_worker.c
COPY start-worker.sh /usr/local/bin/start-worker.sh

RUN mpicc -fopenmp -o /opt/mpi-app/bmp_mpi_worker /opt/mpi-app/bmp_mpi_worker.c -lcrypto

RUN mkdir -p /var/run/sshd /mpi-work \
    && echo "PermitRootLogin yes" >> /etc/ssh/sshd_config \
    && echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config \
    && echo "PasswordAuthentication no" >> /etc/ssh/sshd_config \
    && sed -i 's/\r$//' /usr/local/bin/start-worker.sh \
    && chmod +x /usr/local/bin/start-worker.sh

CMD ["/usr/local/bin/start-worker.sh"]
