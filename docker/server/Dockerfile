FROM maven:3.9.11-eclipse-temurin-17 AS builder

WORKDIR /build
COPY docker/server/settings.xml /root/.m2/settings.xml
COPY aimall-server/pom.xml ./pom.xml
RUN mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline
COPY aimall-server/src ./src
RUN mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=5 clean package

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --gid 10001 aimall \
    && useradd --uid 10001 --gid aimall --create-home --shell /usr/sbin/nologin aimall

WORKDIR /app
COPY --from=builder /build/target/aimall-server-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/storage /app/logs && chown -R aimall:aimall /app

USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=docker \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -XX:MaxRAMPercentage=75"

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /api/health HTTP/1.0\r\n\r\n" >&3 && grep -q "200" <&3' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
