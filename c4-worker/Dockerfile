FROM critoma/amd64_u24_noble_ism_security:latest AS critoma-base

# Reset inherited image metadata so the base image's EXPOSE 8080 is not published.
FROM scratch
COPY --from=critoma-base / /

WORKDIR /opt/mpi-app

ENV JAVA_HOME=/opt/software/java/jdks/jdk-25.0.1/
ENV PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

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
