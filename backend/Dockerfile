# syntax=docker/dockerfile:1

FROM maven:3.10-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 --shell /usr/sbin/nologin appuser
USER appuser
COPY --from=build /workspace/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
# 8080 serves the API; 8081 is the internal management port used by the
# local observability stack (issue #7) when MANAGEMENT_SERVER_PORT is set.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
