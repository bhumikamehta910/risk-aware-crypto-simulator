FROM node:22-bookworm-slim AS frontend
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.10.0-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src/ src/
COPY --from=frontend /ui/dist/ src/main/resources/static/
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /build/target/simulator-*.jar app.jar
ENV SPRING_PROFILES_ACTIVE=prod,render MARKET_MODE=LIVE MARKET_PROVIDER=AUTO
USER 10001:10001
ENTRYPOINT ["java","-Xms32m","-Xmx220m","-Xss512k","-XX:+UseSerialGC","-XX:ActiveProcessorCount=1","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]
