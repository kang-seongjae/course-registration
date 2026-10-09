# 수강신청 사이트 (1단계: 기본 기능)

Java + Spring Boot + MySQL(Docker)로 만든 간단한 수강신청 사이트입니다.

## 기능

- 강의 목록 조회
- 수강 신청 (정원이 차면 거절, 같은 강의 중복 신청 거절)
- 수강 신청 취소
- 내 신청 목록 조회
- 시작 시 샘플 강의 10개, 학생 100명 자동 등록

로그인은 없으며, 화면에서 학생 ID(1~100)를 입력해 학생을 구분합니다.

> 1단계에서는 동시성 처리(락 등)를 **일부러 넣지 않았습니다.**
> 여러 요청이 동시에 들어오면 정원 초과나 중복 신청이 발생할 수 있으며, 다음 단계에서 직접 확인하고 고칠 예정입니다.

## 실행 방법

사전 준비: JDK 21, Docker Desktop

```powershell
# 1. MySQL 실행 (처음 한 번만 이미지 다운로드)
docker compose up -d

# 2. 애플리케이션 실행 (처음 한 번은 Gradle과 라이브러리를 내려받느라 시간이 걸립니다)
.\gradlew.bat bootRun
```

브라우저에서 http://localhost:8080 접속.

종료: 애플리케이션은 `Ctrl + C`, MySQL은 `docker compose down`.
(DB 데이터까지 지우려면 `docker compose down -v`)

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/courses` | 강의 목록 |
| GET | `/api/students/{studentId}/enrollments` | 내 신청 목록 |
| POST | `/api/enrollments` | 수강 신청. 본문: `{"studentId": 1, "courseId": 3}` |
| DELETE | `/api/enrollments/{enrollmentId}?studentId=1` | 수강 신청 취소 |

규칙에 어긋나면 `400` 과 함께 `{"message": "..."}` 를 돌려줍니다.

## 파일 구조

```
course-registration/
├── docker-compose.yml          MySQL 컨테이너 설정
├── build.gradle                빌드 설정 (의존 라이브러리 목록)
├── settings.gradle             프로젝트 이름
├── gradlew / gradlew.bat       Gradle 실행 스크립트 (Gradle 설치 불필요)
├── gradle/wrapper/             Gradle 버전 정보
└── src/main/
    ├── resources/
    │   ├── application.yml     DB 접속 정보 등 애플리케이션 설정
    │   └── static/index.html   웹 화면 (HTML + JavaScript)
    └── java/com/example/courseregistration/
        ├── CourseRegistrationApplication.java   프로그램 시작점
        ├── domain/        Course, Student, Enrollment  (DB 테이블과 1:1 대응하는 엔티티)
        ├── repository/    DB 읽기/쓰기 인터페이스 (Spring Data JPA 가 구현을 자동 생성)
        ├── service/       EnrollmentService (수강 신청 규칙), EnrollmentException
        ├── controller/    HTTP 요청을 받는 곳, GlobalExceptionHandler (오류를 JSON 으로 변환)
        ├── dto/           화면과 주고받는 데이터 형식 (요청/응답 객체)
        └── config/        DataInitializer (시작 시 샘플 데이터 등록)
```

요청 흐름: `index.html` → `controller` → `service` → `repository` → MySQL
