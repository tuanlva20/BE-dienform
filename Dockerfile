# Build stage
FROM maven:3.8.4-openjdk-17-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Install Google Chrome and required dependencies
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget gnupg ca-certificates curl unzip \
    fonts-liberation libasound2 libatk-bridge2.0-0 libatk1.0-0 libcairo2 \
    libgbm1 libgtk-3-0 libnss3 libxcomposite1 libxdamage1 libxfixes3 \
    libxrandr2 libxshmfence1 xdg-utils tzdata \
    && rm -rf /var/lib/apt/lists/*

# Add Google Chrome apt repository and install Chrome
RUN wget -qO- https://dl.google.com/linux/linux_signing_key.pub \
    | gpg --dearmor -o /usr/share/keyrings/google-linux-keyring.gpg && \
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-linux-keyring.gpg] http://dl.google.com/linux/chrome/deb/ stable main" \
    > /etc/apt/sources.list.d/google-chrome.list && \
    apt-get update && apt-get install -y --no-install-recommends google-chrome-stable && \
    rm -rf /var/lib/apt/lists/*

# Add wait-for-it script to handle database connection
ADD https://raw.githubusercontent.com/vishnubob/wait-for-it/master/wait-for-it.sh /wait-for-it.sh
RUN chmod +x /wait-for-it.sh

# Create a non-root user to run the application
RUN addgroup --system spring && adduser --system --ingroup spring spring

# Create logs directory and set permissions
RUN mkdir -p /app/logs /app/logs/archive && \
    chown -R spring:spring /app/logs && \
    chmod -R 755 /app/logs
USER spring:spring

EXPOSE 2412 9092
ENTRYPOINT ["java", "-jar", "app.jar"] 