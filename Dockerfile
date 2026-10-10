# syntax=docker/dockerfile:1

# 1) Frontend bauen
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) Backend bauen, das gebaute Frontend wird ins JAR gepackt (Tests laufen in der CI, nicht hier)
FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /build
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
COPY --from=frontend /build/frontend/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package && cp target/group-purse-*.jar /build/app.jar

# 3) Schlankes Laufzeit-Image
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data/receipts && chown -R app:app /data
USER app
WORKDIR /app
COPY --from=backend /build/app.jar /app/app.jar
ENV STORAGE_DIR=/data/receipts \
    JAVA_TOOL_OPTIONS="-Xmx512m -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -Dfile.encoding=UTF-8"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
