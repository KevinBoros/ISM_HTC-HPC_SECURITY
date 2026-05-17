FROM critoma/amd64_u24_noble_ism_security:latest

WORKDIR /opt/mpi-app

RUN mkdir -p /opt/mpi-app /mpi-work /var/run/sshd

COPY mpi/bmp_mpi_worker.c /opt/mpi-app/bmp_mpi_worker.c
COPY start-worker.sh /usr/local/bin/start-worker.sh

RUN mpicc -fopenmp -o /opt/mpi-app/bmp_mpi_worker /opt/mpi-app/bmp_mpi_worker.c -lcrypto

RUN echo "PermitRootLogin yes" >> /etc/ssh/sshd_config \
    && echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config \
    && echo "PasswordAuthentication no" >> /etc/ssh/sshd_config \
    && sed -i 's/\r$//' /usr/local/bin/start-worker.sh \
    && chmod +x /usr/local/bin/start-worker.sh

EXPOSE 22

CMD ["/usr/local/bin/start-worker.sh"]