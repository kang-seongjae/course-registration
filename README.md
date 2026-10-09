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

## 2단계: 수정 전 결과

동시성 문제를 확인하기 위한 테스트 `src/test/java/.../EnrollmentConcurrencyTest.java` 를 추가했다.
정원 30명인 강의(CS101)에 서로 다른 학생 100명이 CountDownLatch 로 같은 순간에 신청한다.
실제 MySQL(docker compose)에 대해 실행하며, 매 실행 전 CS101 의 신청 내역을 지우고 인원을 0으로 되돌린다.

```powershell
docker compose up -d
.\gradlew.bat test --tests "*EnrollmentConcurrencyTest" --rerun
```

동시성 처리가 없는 상태에서 3번 실행한 결과:

| 실행 | 신청 성공 | 규칙 거절 | 기타 오류(데드락) | 강의의 신청 인원 (enrolled_count) | 실제 신청 내역 행 수 |
|---|---|---|---|---|---|
| 1회 | 23 | 0 | 77 | 13 | 23 |
| 2회 | 22 | 0 | 78 | 12 | 22 |
| 3회 | 23 | 0 | 77 | 12 | 23 |

테스트는 3번 모두 실패(FAILED). 확인된 문제:

1. **갱신 유실**: 실제 신청 행은 23개인데 `enrolled_count` 는 13. 모든 스레드가 같은 값(0)을 읽고
   각자 1을 더한 뒤 `enrolled_count = <계산한 값>` 으로 덮어쓰기 때문에 다른 스레드의 증가분이 사라진다.
   (`EnrollmentService.enroll` 의 `course.increaseEnrolledCount()` 가 메모리 값만 바꾸고 JPA 가 UPDATE 로 덮어쓴다)
2. **낡은 값으로 정원 검사**: `course.isFull()` 은 자기 트랜잭션이 읽은 시점의 숫자만 본다.
   100개 스레드가 동시에 0을 읽으면 전부 "정원 안 찼음"으로 판단한다. 이번에는 데드락에 가려졌지만
   데드락이 없으면 여기서 정원 초과가 발생한다.
3. **데드락**: `enrollments` 가 `courses` 를 외래 키로 참조하므로 INSERT 시 courses 행에 공유 잠금(S)이 걸리고,
   이어지는 courses UPDATE 는 같은 행의 배타 잠금(X)을 기다린다. 두 트랜잭션이 서로 S 를 쥔 채 X 를 기다려
   MySQL 이 한쪽을 강제 종료한다. (`SHOW ENGINE INNODB STATUS` 로 확인)

정원을 넘기지 않은 것은 로직이 막아서가 아니라 요청의 77% 가 데드락으로 튕겨 나갔기 때문이다.

## 2단계: 수정 후 결과

비관적 락(`SELECT ... FOR UPDATE`)으로 해결했다.

- `CourseRepository.findByIdForUpdate()` 추가: `@Lock(LockModeType.PESSIMISTIC_WRITE)` 를 붙인 조회 메서드.
  실행되는 SQL 은 `select ... from courses c1_0 where c1_0.id=? for update of c1_0`.
- `EnrollmentService.enroll()` / `cancel()`: 강의를 `findById()` 대신 `findByIdForUpdate()` 로 읽는다.

동작 원리: 트랜잭션이 강의 행을 FOR UPDATE 로 읽는 순간 그 행에 배타 잠금(X)이 걸린다.
같은 강의를 처리하려는 다른 트랜잭션은 FOR UPDATE 지점에서 앞 트랜잭션이 커밋될 때까지 기다린다.
그래서 같은 강의에 대한 신청/취소는 한 번에 하나씩만 진행되고,

1. 정원 검사(`isFull`)는 항상 방금 커밋된 최신 값을 본다 → 정원 초과 없음
2. 인원 증가는 앞 트랜잭션의 결과 위에 쌓인다 → 갱신 유실 없음
3. 자기 트랜잭션이 이미 X 잠금을 쥔 상태에서 INSERT(FK 검사의 S 잠금)와 UPDATE 를 하므로
   다른 트랜잭션과 잠금을 교차해서 기다릴 일이 없다 → 데드락 없음

같은 테스트를 3번 실행한 결과:

| 실행 | 신청 성공 | 규칙 거절 | 기타 오류 | 강의의 신청 인원 (enrolled_count) | 실제 신청 내역 행 수 |
|---|---|---|---|---|---|
| 1회 | 30 | 70 | 0 | 30 | 30 |
| 2회 | 30 | 70 | 0 | 30 | 30 |
| 3회 | 30 | 70 | 0 | 30 | 30 |

테스트는 3번 모두 통과(PASSED).
