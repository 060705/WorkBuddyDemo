FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/demo-0.0.6.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
