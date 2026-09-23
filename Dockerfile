# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY target/*.jar app.jar

RUN chown app:app /app/app.jar
USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
