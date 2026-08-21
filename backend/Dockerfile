FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN useradd --system --uid 10001 forgepilot
COPY --from=build /workspace/target/forgepilot-backend.jar /app/forgepilot-backend.jar
RUN chown -R forgepilot:forgepilot /app
USER forgepilot
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx384m -XX:MaxDirectMemorySize=128m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:NativeMemoryTracking=summary"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 \
  CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/forgepilot-backend.jar"]
