FROM critoma/amd64_u24_noble_ism_security:latest
WORKDIR /home/c3-consumer

ENV JAVA_HOME=/opt/software/java/jdks/jdk-25.0.1/
ENV PATH=$JAVA_HOME/bin:$PATH

COPY src/ src/
COPY pom.xml pom.xml
COPY mvnw mvnw
COPY .mvn/ .mvn/
COPY mpi/ mpi/

RUN sed -i 's/\r$//' mvnw
RUN ./mvnw clean package

RUN mpicc -Wall -Wextra -O0 -g -o /home/c3-consumer/mpi/bmp_mpi_worker /home/c3-consumer/mpi/bmp_mpi_worker.c -lcrypto

RUN mkdir -p /data/output

CMD ["java", "-jar", "target/c3-consumer-1.0.jar"]