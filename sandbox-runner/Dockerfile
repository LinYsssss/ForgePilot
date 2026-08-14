FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM docker:29.7.2-cli AS docker-cli

FROM eclipse-temurin:17.0.19_10-jre-alpine
# 把 alpine 软件源里已发布的安全修复(libexpat/p11-kit 等)收进来。
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /workspace/target/sandbox-runner-0.1.0-SNAPSHOT.jar /app/sandbox-runner.jar
ENTRYPOINT ["java", "-jar", "/app/sandbox-runner.jar"]
