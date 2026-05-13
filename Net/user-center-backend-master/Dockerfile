FROM maven:3.9.9-eclipse-temurin-8 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -Dmaven.test.skip=true

COPY src ./src
RUN mvn package -Dmaven.test.skip=true

CMD ["java","-jar","/app/target/user-center-backend-0.0.1-SNAPSHOT.jar","--spring.profiles.active=prod"]
