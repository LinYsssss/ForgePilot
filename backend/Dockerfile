FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# 固定补丁位标签保证可复现;镜像扫描(deploy/scan-images.sh)不过就在这里升。
FROM eclipse-temurin:17.0.19_10-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    # 基础镜像自带的 pebble(Canonical 服务管理器)在这里用不上,却携带旧 Go 依赖的 CVE,删掉。
    && rm -f /usr/bin/pebble
COPY --from=build /app/target/code-review-platform-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
