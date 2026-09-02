FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache font-wqy-zenhei \
    && addgroup -S quotation && adduser -S quotation -G quotation
WORKDIR /app
COPY --from=jar quotation-backend-0.1.0.jar app.jar
USER quotation
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
