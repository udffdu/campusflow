FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S campusflow && adduser -S campusflow -G campusflow
WORKDIR /app
COPY --from=build /build/target/platform-0.1.0-SNAPSHOT.jar app.jar
USER campusflow
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
