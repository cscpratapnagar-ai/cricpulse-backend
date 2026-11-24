# syntax=docker/dockerfile:1

# Build the application inside the image so clean Git-based platforms (including
# Vercel Container Runtime) do not depend on a pre-existing, ignored target/ folder.
FROM maven:3.9.11-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Keep the runtime image small and run the application as a non-root user.
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/target/*.jar /app/app.jar

USER app
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
