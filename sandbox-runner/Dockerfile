FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM docker:27.5.1-cli AS docker-cli

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /workspace/target/sandbox-runner-0.1.0-SNAPSHOT.jar /app/sandbox-runner.jar
ENTRYPOINT ["java", "-jar", "/app/sandbox-runner.jar"]
