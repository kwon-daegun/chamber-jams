FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 ffmpeg nodejs curl ca-certificates && \
    curl -L https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && \
    chmod a+rx /usr/local/bin/yt-dlp && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/audio_cache && chmod -R 777 /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 7860

ENTRYPOINT ["sh", "-c", "java -Xmx350m -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Dserver.port=${PORT:-7860} -jar app.jar"]