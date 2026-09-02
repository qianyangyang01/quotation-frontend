FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache font-wqy-zenhei \
    && addgroup -S quotation && adduser -S quotation -G quotation
WORKDIR /app
COPY --from=build /workspace/target/quotation-backend-0.1.0.jar app.jar
USER quotation
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
