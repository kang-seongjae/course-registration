# Spring Boot 앱을 Linux 컨테이너로 실행하기 위한 이미지 정의 (4단계-3)
#
# 빌드 순서: 1) .\gradlew.bat bootJar  2) docker compose --profile app up -d --build
# 컨테이너 안에서 Gradle 빌드를 하지 않고, 이미 만든 jar 파일만 복사한다.
# (Gradle 과 라이브러리를 컨테이너 안에서 다시 내려받지 않아도 되므로 빠르고 단순하다)
FROM eclipse-temurin:21-jdk-jammy

# ss 명령(iproute2)은 Tomcat 소켓의 접속 대기열 크기를 확인하는 실험에 쓴다
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY build/libs/course-registration-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
