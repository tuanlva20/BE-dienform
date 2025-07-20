# Build stage
FROM maven:3.8.4-openjdk-17-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Add wait-for-it script to handle database connection
ADD https://raw.githubusercontent.com/vishnubob/wait-for-it/master/wait-for-it.sh /wait-for-it.sh
RUN chmod +x /wait-for-it.sh

# Create a non-root user to run the application
RUN addgroup -S spring && adduser -S spring -G spring

# Create logs directory and set permissions
RUN mkdir -p /app/logs /app/logs/archive && \
    chown -R spring:spring /app/logs && \
    chmod -R 755 /app/logs
USER spring:spring

EXPOSE 2412
ENTRYPOINT ["java", "-jar", "app.jar"] 