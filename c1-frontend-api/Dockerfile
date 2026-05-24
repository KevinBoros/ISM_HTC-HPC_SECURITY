FROM critoma/amd64_u24_noble_ism_security:latest AS critoma-base

# Reset inherited image metadata so the base image's EXPOSE 8080 is not published.
FROM scratch
COPY --from=critoma-base / /

WORKDIR /home/c1-frontend-api

ENV JAVA_HOME=/opt/software/java/jdks/jdk-25.0.1/
ENV PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

COPY src/ src/
COPY pom.xml pom.xml
COPY mvnw mvnw
COPY .mvn/ .mvn/

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw clean package
EXPOSE 7071

CMD ["java", "-jar", "target/javalin-c1-backend-1.0.jar"]
