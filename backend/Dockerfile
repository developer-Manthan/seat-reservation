# Build stage: tests are skipped here (they need a real MySQL, run them locally against compose).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs && chown -R app:app /app

COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

ENV LOG_DIR=/app/logs \
    JAVA_OPTS=""

USER app
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
