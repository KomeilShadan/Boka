FROM eclipse-temurin:25.0.4.1_1-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -Dspotless.skip=true dependency:go-offline
COPY src/main src/main
RUN ./mvnw -B -Dspotless.skip=true -Dmaven.test.skip=true package

FROM eclipse-temurin:25.0.4.1_1-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 boka && useradd --uid 10001 --gid boka --no-create-home boka
WORKDIR /app
COPY --from=build --chown=boka:boka /build/target/boka-0.0.1-SNAPSHOT.jar app.jar
USER boka
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
