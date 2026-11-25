# Build: compile the service with the Maven wrapper (tests run in CI / locally, not on every deploy).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package && cp target/api-management-service-*.jar app.jar

# Run: small JRE image. Vercel sends traffic to $PORT (default 80).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
ENV PORT=80
# Faster startup for scale-to-zero (cold starts) and a memory cap that fits the 2 GB limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:TieredStopAtLevel=1", "-jar", "app.jar"]
