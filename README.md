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

## 3단계: 부하 테스트 결과(개선 전)

k6 로 부하를 걸어 측정만 했다. (성능 개선은 아직 하지 않음)

- 시나리오 (`load-test/enroll.js`): 가상 사용자(VU)마다 서로 다른 학생 ID 로 강의 목록을 조회한 뒤 강의 하나를 무작위로 골라 신청한다. VU 당 1회.
- 샘플 학생을 5,000명으로 늘렸다 (`DataInitializer`, 부족한 만큼만 추가).
- SQL 로그를 끈 `load` 프로필(`application-load.yml`)로 jar 를 실행해서 측정했다.
- 정원 초과 거절(400)은 오류가 아니라 정상 응답으로 집계했다.
- 매 실행 전 `load-test/reset.sql` 로 신청 데이터를 비우고, 실행 후 `load-test/verify.sql` 로 정합성을 확인했다.

```powershell
docker compose up -d
.\gradlew.bat bootJar
java -jar build\libs\course-registration-0.0.1-SNAPSHOT.jar --spring.profiles.active=load
# 다른 터미널에서
docker exec -i course-registration-mysql mysql -uapp -papp1234 course_registration < load-test/reset.sql
k6 run -e VUS=500 load-test/enroll.js
docker exec -i course-registration-mysql mysql -uapp -papp1234 course_registration < load-test/verify.sql
```

### 측정 결과

| VU | 총 요청 수 | 평균 응답 | p95 응답 | 초당 처리 요청 | 오류 비율 | 신청 성공 | 정원 초과 거절 | 연결 거절 |
|---|---|---|---|---|---|---|---|---|
| 500 | 1,000 | 1,135 ms | 2,102 ms | 309 /s | 0.0% | 151 | 349 | 0 |
| 2,000 | 3,406 | 2,005 ms | 3,941 ms | 486 /s | 17.4% | 151 | 1,255 | 594 |
| 5,000 | 7,641 | 3,001 ms | 5,981 ms | 595 /s | 30.9% | 151 | 2,490 | 2,359 |

요청 종류별 응답 시간:

| VU | 목록 조회 평균 | 목록 조회 p95 | 신청 평균 | 신청 p95 |
|---|---|---|---|---|
| 500 | 707 ms | 1,552 ms | 1,563 ms | 2,305 ms |
| 2,000 | 1,057 ms | 3,039 ms | 3,355 ms | 4,164 ms |
| 5,000 | 1,770 ms | 3,866 ms | 5,332 ms | 6,042 ms |

- 오류는 전부 `dial: connection refused` (서버가 TCP 연결 자체를 거절). 애플리케이션 오류(5xx)는 0건, 서버 로그의 예외도 0건.
- 연결이 거절된 VU 는 목록 조회부터 실패하므로 신청까지 가지 못했다. 500 VU 는 경계선이라 실행에 따라 0 ~ 222건이 거절되기도 했다.
- 신청 성공 151 = 10개 강의 정원 합계. 세 번 모두 정원만큼만 성공했다.

### DB 정합성 (매 실행 후 확인)

| VU | 정원 초과 강의 수 | enrolled_count ≠ 실제 행 수인 강의 수 | 총 신청 행 수 |
|---|---|---|---|
| 500 | 0 | 0 | 151 |
| 2,000 | 0 | 0 | 151 |
| 5,000 | 0 | 0 | 151 |

### 병목 분석

**1. DB 커넥션 풀 (가장 큰 병목)** — HikariCP 기본 최대 커넥션은 10개, Tomcat 기본 요청 처리 스레드는 200개다.
5,000 VU 실행 3초 시점의 서버 스레드 덤프(jstack)에서 HTTP 스레드 202개의 상태:

| 상태 | 스레드 수 |
|---|---|
| DB 커넥션을 받으려고 대기 (`HikariPool.getConnection`) | 190 |
| MySQL 응답 대기 (커넥션을 쥐고 실제 쿼리 실행 중) | 10 |
| 유휴 / 기타 | 2 |

즉 요청 200개가 동시에 들어와도 실제로 DB 작업을 하는 건 10개뿐이고 나머지 190개는 줄을 서 있다.
목록 조회는 락과 무관한 단순 SELECT 인데도 평균 0.7 ~ 1.8초가 걸린 이유가 이것이다 (커넥션을 받을 때까지 기다리는 시간).
한 요청이 대기 → 커넥션 획득 → 쿼리 → 반납을 거치므로 전체 처리량이 커넥션 10개의 회전 속도에 묶인다.

**2. 비관적 락은 부수적** — MySQL `Innodb_row_lock_waits` 증가분은 5,000 VU 실행에서 697건,
누적 대기 시간 4.8초로 신청 1건당 평균 약 7ms. 응답 시간 수 초에 비하면 작다.
다만 같은 강의의 신청은 한 줄로 서므로, 커넥션을 쥔 채 락을 기다리는 트랜잭션이 커넥션 회전을 더 늦춘다.

**3. 접속 수락 큐 (오류 비율의 원인)** — Tomcat 의 `accept-count` 기본값은 100 이다.
2,000 / 5,000 VU 가 동시에 TCP 연결을 열면 수락 대기열이 넘쳐 OS 가 나머지를 거절한다.
거절은 실행 시작 1 ~ 2초 안에 몰려서 발생했고(k6 진행 로그), 거절된 요청은 서버 로그에 아예 남지 않는다.

정리하면, 응답 시간은 커넥션 풀(10개)에서, 오류는 접속 수락 큐(100개)에서 생긴다. 둘 다 아직 기본값 그대로다.

## 4단계-1: 설정 조정 결과

3단계와 같은 시나리오(500 / 2,000 / 5,000 VU, 매 실행 전 초기화, 실행 후 정합성 확인)로
설정을 한 번에 하나씩 바꿔 가며 측정했다. 설정은 jar 실행 인자로 주었다.

| 단계 | 설정 | 실행 인자 |
|---|---|---|
| 개선 전 | accept-count 100, 풀 10 (기본값) | 없음 |
| ① 접속 큐 | accept-count 5000, 풀 10 | `--server.tomcat.accept-count=5000` |
| ② 풀 30 | accept-count 5000, 풀 30 | `… --spring.datasource.hikari.maximum-pool-size=30` |
| ② 풀 50 | accept-count 5000, 풀 50 | `… --spring.datasource.hikari.maximum-pool-size=50` |

MySQL `max_connections` 는 기본값 151 이다. 풀 50 + 다른 접속을 합쳐도 `Max_used_connections` 는 61 로 여유가 있었다.

목표: 5,000 VU 에서 오류 비율 1% 미만, p95 3초 이내 → **달성하지 못함**

### 5,000 VU 비교 (개선 전 vs 각 설정)

| 설정 | 평균 응답 | p95 응답 | 초당 처리 | 오류 비율 | 연결 거절 | 신청 p95 | 목록 조회 p95 | 정합성 |
|---|---|---|---|---|---|---|---|---|
| 개선 전 | 3,001 ms | 5,981 ms | 595 /s | 30.9% | 2,359 | 6,042 ms | 3,866 ms | 정상 |
| ① accept-count 5000 | 3,117 ms | 6,129 ms | 568 /s | 31.2% | 2,380 | 6,433 ms | 3,902 ms | 정상 |
| ② 풀 30 | 3,977 ms | 6,412 ms | 590 /s | 20.3% | 1,684 | 6,455 ms | 5,564 ms | 정상 |
| ② 풀 50 | 2,014 ms | 4,407 ms | 734 /s | 39.2% | 2,814 | 4,637 ms | 3,358 ms | 정상 |

### 모든 VU 단계 (p95 / 오류 비율)

| VU | 개선 전 | ① accept-count 5000 | ② 풀 30 | ② 풀 50 |
|---|---|---|---|---|
| 500 | 2,102 ms / 0.0% | 2,762 ms / 0.0% | 2,640 ms / 0.0% | 2,434 ms / 0.0% |
| 2,000 | 3,941 ms / 17.4% | 4,032 ms / 23.5% | 4,064 ms / 19.2% | 3,452 ms / 23.3% |
| 5,000 | 5,981 ms / 30.9% | 6,129 ms / 31.2% | 6,412 ms / 20.3% | 4,407 ms / 39.2% |

연결 거절 수는 실행마다 수백 건씩 흔들린다(같은 설정으로 다시 돌려도 2,380 → 3,001 등). 20 ~ 40% 사이의 차이는 설정 효과가 아니라 편차로 본다.
DB 정합성은 12번의 실행 모두 정원 초과 0, 불일치 0 이었다.

### 서버 내부 지표 (5,000 VU, 실행 3초 시점)

| 설정 | DB 커넥션 대기 스레드 | DB 작업 중 스레드 | 행 락 대기 횟수 | 행 락 누적 대기 시간 |
|---|---|---|---|---|
| 풀 10 | 190 | 10 | 692 | 5.0 초 |
| 풀 30 | 170 | 30 | 2,166 | 65.4 초 |
| 풀 50 | 149 | 49 | 1,719 | 126.8 초 |

### 각 설정이 무엇이고 왜 효과가 있었나 / 없었나

**① accept-count (접속 수락 대기열)** — 서버가 아직 `accept()` 하지 못한 TCP 연결을 OS 가 잠시 세워 두는 줄의 길이다.
줄이 꽉 차면 OS 가 새 연결을 거절한다("connection refused"). 100 → 5,000 으로 올렸지만 거절 수는 그대로였다.
원인은 Windows 가 이 줄을 **200 으로 제한**하기 때문이다. 파이썬으로 `listen(5000)` 소켓을 만들고 accept 없이 연결을 넣어 보면
정확히 200개째까지만 대기열에 들어가고 201번째부터 실패한다(`listen(200)` 과 동일). 즉 Tomcat 설정은 OS 에 의해 200 으로 잘린다.
Linux 에서는 `net.core.somaxconn`(기본 4096) 안에서 설정값이 그대로 적용되므로, 이 설정은 Linux 배포 시에는 의미가 있다.

**② 커넥션 풀 크기** — 서버가 MySQL 에 미리 열어 두고 돌려 쓰는 연결의 개수다. 3단계에서 HTTP 스레드 200개 중 190개가
커넥션을 기다리고 있었으므로, 풀을 키우면 동시에 DB 작업을 하는 요청이 늘어난다.
- 풀 50 은 5,000 VU 에서 평균 3.0 → 2.0초, p95 6.0 → 4.4초, 처리량 595 → 734/s 로 **가장 좋은 결과**였다. 커넥션을 받기까지의 대기가 줄었기 때문이다.
- 그러나 기대만큼 늘지 않았다. 풀을 늘리자 **행 락 대기 시간이 5초 → 65초 → 127초로 폭증**했다. 같은 강의의 신청은 `FOR UPDATE` 때문에 한 줄로 서야 하는데,
  커넥션이 많아지면 "커넥션을 쥔 채 락을 기다리는" 트랜잭션만 늘어난다. 줄 서는 장소가 커넥션 풀 앞에서 DB 행 락 앞으로 옮겨간 셈이다.
- 풀 30 이 풀 10 보다 나아 보이지 않은 것도 같은 이유다. 락 대기가 늘어난 만큼 커넥션 대기가 줄어든 효과가 상쇄됐다.

### 다음 병목 (근거)

1. **Windows 접속 대기열 200 제한** — 오류의 전부가 여기서 난다. 애플리케이션 설정으로는 못 넘는다.
   선택지는 서버를 Linux(Docker 컨테이너 등)에서 띄우기, 앞에 리버스 프록시를 두기, 또는 5,000명이 "같은 밀리초"에 접속하는 대신 몇 초에 걸쳐 들어오게 하기다.
2. **같은 강의 행에 대한 락 경합** — 풀 50 에서 행 락 누적 대기 127초, 신청 p95 4.6초. 신청 트랜잭션 하나가 락을 쥔 채
   학생 조회 → FOR UPDATE → 중복 검사 → INSERT → UPDATE 다섯 문장을 보내므로 락을 쥐는 시간이 길다.
   락을 쥐는 구간을 줄이거나(문장 수 축소), 정원 검사와 증가를 `UPDATE ... WHERE enrolled_count < capacity` 한 문장으로 합치는 식의 코드 개선이 필요하다.
3. **PC 자체의 CPU 포화** — 풀 50 으로 5,000 VU 를 돌리는 동안 6코어 전체 CPU 가 100% 였다
   (Java 서버 약 3.3코어, Docker(MySQL) 약 1.3코어, 나머지가 k6). 서버·DB·부하 발생기가 한 PC 에 있어 처리량 상한이 600 ~ 730/s 에서 막힌다.
   설정을 더 바꿔도 이 상한은 안 움직이므로, 정확한 서버 한계를 보려면 k6 를 다른 머신에서 돌려야 한다.

### 반영한 설정 (`application.yml`)

- `spring.datasource.hikari.maximum-pool-size: 50`
- `server.tomcat.accept-count: 1000` (Windows 에서는 효과 없음, Linux 용)
