FROM gradle:9.3.0-jdk17 AS builder

WORKDIR /app

COPY build.gradle settings.gradle gradlew gradlew.bat /app/
COPY gradle /app/gradle
COPY src /app/src

RUN chmod +x /app/gradlew
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS=""

COPY --from=builder /app/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
