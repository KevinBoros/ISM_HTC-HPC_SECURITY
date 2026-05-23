FROM critoma/amd64_u24_noble_ism_security:latest

WORKDIR /home/c3-consumer

ENV JAVA_HOME=/opt/software/java/jdks/jdk-25.0.1/
ENV PATH=$JAVA_HOME/bin:$PATH

RUN mkdir -p /opt/mpi-app /mpi-work /data/output /var/run/sshd \
    && echo "PermitRootLogin yes" >> /etc/ssh/sshd_config \
    && echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config \
    && echo "PasswordAuthentication no" >> /etc/ssh/sshd_config

COPY src/ src/
COPY pom.xml pom.xml
COPY mvnw mvnw
COPY .mvn/ .mvn/
COPY mpi/bmp_mpi_worker.c /opt/mpi-app/bmp_mpi_worker.c
COPY start-c3.sh /usr/local/bin/start-c3.sh

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw clean package

RUN mpicc -fopenmp -o /opt/mpi-app/bmp_mpi_worker /opt/mpi-app/bmp_mpi_worker.c -lcrypto

RUN sed -i 's/\r$//' /usr/local/bin/start-c3.sh \
    && chmod +x /usr/local/bin/start-c3.sh

CMD ["/usr/local/bin/start-c3.sh"]