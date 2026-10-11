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

## 4단계-2: 락 구간 축소 결과

4단계-1에서 "같은 강의 행에 대한 락 경합"이 다음 병목으로 꼽혔다. 신청 트랜잭션 하나가 락을 쥔 채
여러 문장을 보내므로 락을 쥐는 시간이 길었다. 이번 단계에서는 **락을 쥐는 구간을 줄이는 것**이 목표다.

### 바꾸기 전에: 왜 이 방식이 락 시간을 줄이는가

2단계 방식(`SELECT ... FOR UPDATE`)의 신청 흐름은 이랬다. 번호는 DB 로 보내는 문장 순서다.

```
1. SELECT 학생                 (락 없음)
2. SELECT 강의 FOR UPDATE      ← 여기서 강의 행이 잠긴다
3. SELECT 중복 신청 여부
4. INSERT 신청 내역
5. UPDATE 강의 (enrolled_count = 자바가 계산한 값)
6. COMMIT                      ← 여기서 풀린다
```

강의 행은 2번부터 6번까지 잠겨 있다. 그 사이 자바는 DB 응답을 받고 → 다음 문장을 만들고 → 다시 보내기를 네 번 반복한다.
같은 강의를 신청하려는 다른 요청은 전부 2번 지점에서 이 과정이 끝나기를 기다린다.
은행 창구에 비유하면, 창구 직원이 손님 한 명의 서류를 들고 있는 동안 뒷줄 전체가 멈춰 있는 것과 같다.
줄이 줄어드는 속도는 "한 명을 처리하는 시간"에 달려 있으므로, 그 시간을 줄이는 것이 핵심이다.

조건부 UPDATE 방식은 2번과 5번(검사와 증가)을 DB 안에서 한 문장으로 처리한다.

```sql
update courses
   set enrolled_count = enrolled_count + 1
 where id = ? and enrolled_count < capacity
```

- `enrolled_count < capacity` 가 정원 검사다. 조건에 맞는 행이 없으면 아무것도 바꾸지 않는다.
- `enrolled_count + 1` 은 자바가 계산한 값이 아니라 **DB 가 지금 저장된 값에 더한다.** 다른 요청의 증가분을 덮어쓸 일이 없다.
- 실행 결과로 "바뀐 행 수"가 돌아온다. 1 이면 신청 성공, 0 이면 정원이 찬 것이다. 즉 검사 결과를 따로 읽어 올 필요가 없다.
- DB 는 이 UPDATE 를 실행하는 동안 행을 잠그고, 트랜잭션이 끝날 때 푼다. 자바가 "읽고 → 생각하고 → 쓰는" 왕복이 사라진다.

바꾼 뒤의 흐름은 이렇다.

```
1. SELECT 학생                 (락 없음)
2. SELECT 강의                 (락 없음, 이름/정원을 메시지에 쓰기 위해)
3. SELECT 중복 신청 여부        (락 없음)
4. UPDATE 강의 (조건부)         ← 여기서 잠긴다
5. INSERT 신청 내역
6. COMMIT                      ← 여기서 풀린다
```

잠긴 상태로 보내는 문장이 4개(3, 4, 5, 6)에서 3개(4, 5, 6)로 줄었다.

**주의할 점**

1. **INSERT 는 UPDATE 뒤에, 같은 트랜잭션 안에 있어야 한다.** INSERT 를 먼저 하면 2단계에서 본 데드락이 되살아난다.
   `enrollments` 가 `courses` 를 외래 키로 참조하므로 INSERT 는 강의 행에 공유 잠금(S)을 건다.
   두 요청이 S 를 쥔 채 UPDATE 의 배타 잠금(X)을 서로 기다리면 데드락이다. UPDATE(X)를 먼저 잡으면 INSERT 의 S 는 자기 것이라 문제없다.
   그래서 락 구간이 0 이 되지는 않는다. INSERT 와 COMMIT 은 여전히 락 안에 있다.
2. **정원이 찬 강의에 대한 UPDATE 도 행을 잠근다.** MySQL 기본 격리 수준(REPEATABLE READ)에서는 UPDATE 가 PK 로 찾은 행을
   조건(`enrolled_count < capacity`)이 안 맞아도 트랜잭션 끝까지 잠근다. 정원이 찬 뒤에 몰려오는 수천 건의 요청이
   "거절당하기 위해" 락 줄에 서는 셈이다. 이 문제는 아래 측정에서 실제로 드러났고, 락 없는 선검사로 해결했다.
3. **메모리의 Course 객체는 낡은 값을 갖는다.** 2번에서 락 없이 읽은 `enrolledCount` 는 UPDATE 이후의 값과 다르다.
   이 객체는 이름과 정원을 메시지에 쓰는 용도로만 쓰고, 값을 바꿔서 저장하면 안 된다. (바꾸면 JPA 가 UPDATE 로 덮어써서 갱신 유실이 다시 생긴다)
4. **강의 행 락이 사라지면 중복 신청 검사에 빈틈이 생긴다.** 아래에서 따로 다룬다.

### 같은 학생의 중복 신청은 어떻게 막나

2단계에서는 FOR UPDATE 가 같은 강의에 대한 모든 신청을 한 줄로 세웠기 때문에, "중복 검사(3번) → INSERT(4번)" 사이에
같은 학생의 다른 요청이 끼어들 수 없었다. 이제 3번은 락 없이 실행되므로 같은 학생의 요청 10개가 동시에 오면
10개 모두 "아직 신청 안 함"을 보고 INSERT 까지 갈 수 있다.

그래서 `enrollments` 테이블에 `(student_id, course_id)` **유니크 제약**을 걸었다 (`uk_enrollments_student_course`).
DB 는 같은 조합의 두 번째 INSERT 를 거부하고 `DataIntegrityViolationException` 을 던진다.
서비스는 이것을 잡아 `EnrollmentException("이미 신청한 강의입니다")` 로 바꾼다. 예외가 던져지면 트랜잭션이 롤백되므로
바로 앞 UPDATE 로 늘린 인원도 함께 되돌아간다.

코드의 `existsByStudentIdAndCourseId` 검사는 그대로 두었다. 대부분의 중복 신청(화면에서 두 번 누르기 등)은
여기서 걸려 락을 잡기 전에 거절되고, 동시에 들어온 경우만 유니크 제약까지 간다. 즉 **코드 검사는 1차, 유니크 제약이 최종** 이다.

유니크 제약은 Hibernate `ddl-auto: update` 가 기존 테이블에 자동으로 추가했다 (`SHOW CREATE TABLE enrollments` 로 확인).
기존 데이터에 중복 조합이 있었다면 추가에 실패했을 것이므로, 먼저 151행 / 151개 조합으로 중복이 없음을 확인했다.

### 수정 전후 코드 비교

**CourseRepository**

```java
// 수정 전 (2단계): 락을 걸고 읽는다
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Course c where c.id = :id")
Optional<Course> findByIdForUpdate(@Param("id") Long id);

// 수정 후 (4단계-2): 검사와 증가를 한 문장으로. 반환값은 바뀐 행 수
@Modifying
@Query("update Course c set c.enrolledCount = c.enrolledCount + 1 "
        + "where c.id = :id and c.enrolledCount < c.capacity")
int increaseEnrolledCountIfNotFull(@Param("id") Long id);

@Modifying
@Query("update Course c set c.enrolledCount = c.enrolledCount - 1 "
        + "where c.id = :id and c.enrolledCount > 0")
int decreaseEnrolledCount(@Param("id") Long id);
```

**EnrollmentService.enroll()**

```java
// 수정 전 (2단계)
Student student = findStudent(studentId);
Course course = courseRepository.findByIdForUpdate(courseId)        // ← 락 시작
        .orElseThrow(...);
if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
    throw new EnrollmentException("이미 신청한 강의입니다: " + course.getName());
}
if (course.isFull()) {                                             // 자바가 검사
    throw new EnrollmentException("정원이 가득 찼습니다: ...");
}
course.increaseEnrolledCount();                                    // 자바가 더하고 JPA 가 UPDATE
return enrollmentRepository.save(new Enrollment(student, course)); // 커밋 때 락 해제

// 수정 후 (4단계-2)
Student student = findStudent(studentId);
Course course = courseRepository.findById(courseId)                 // 락 없는 일반 조회
        .orElseThrow(...);
if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) { // 1차 검사
    throw new EnrollmentException("이미 신청한 강의입니다: " + course.getName());
}
if (course.isFull()) {                                             // 락 없는 선검사 (②)
    throw new EnrollmentException("정원이 가득 찼습니다: ...");
}
int updated = courseRepository.increaseEnrolledCountIfNotFull(courseId); // ← 락 시작, 최종 검사 (①)
if (updated == 0) {
    throw new EnrollmentException("정원이 가득 찼습니다: ...");
}
try {
    return enrollmentRepository.save(new Enrollment(student, course));   // 커밋 때 락 해제
} catch (DataIntegrityViolationException e) {                            // 유니크 제약 = 최종 중복 검사
    throw new EnrollmentException("이미 신청한 강의입니다: " + course.getName());
}
```

**EnrollmentService.cancel()** 도 같은 방식으로 바꿨다. 락을 걸고 읽는 대신, 신청 내역을 먼저 지우고(`delete ... where id = ?`,
지운 행 수가 0 이면 "이미 취소됨") 실제로 지워졌을 때만 `enrolled_count - 1` UPDATE 를 보낸다.
같은 신청을 동시에 두 번 취소해도 인원이 두 번 줄지 않는다.

**Enrollment** 엔티티에 `@UniqueConstraint(name = "uk_enrollments_student_course", columnNames = {"student_id", "course_id"})` 를 추가했다.

### 확인 1, 2: 동시성 테스트

`EnrollmentConcurrencyTest` 에 두 번째 테스트를 추가했다. 같은 학생(ID 1)이 CS101 에 10개 스레드로 동시에 신청한다.

```powershell
docker compose up -d
.\gradlew.bat test --tests "*EnrollmentConcurrencyTest" --rerun
```

| 실행 | 테스트 1 (학생 100명 → 정원 30) | 테스트 2 (같은 학생 10번) |
|---|---|---|
| 1회 | 성공 30, 거절 70, 오류 0, 인원 30, 행 30 — PASSED | 성공 1, 거절 9, 오류 0, 인원 1, 행 1 — PASSED |
| 2회 | 성공 30, 거절 70, 오류 0, 인원 30, 행 30 — PASSED | 성공 1, 거절 9, 오류 0, 인원 1, 행 1 — PASSED |
| 3회 | 성공 30, 거절 70, 오류 0, 인원 30, 행 30 — PASSED | 성공 1, 거절 9, 오류 0, 인원 1, 행 1 — PASSED |

테스트 2의 거절 메시지는 모두 "이미 신청한 강의입니다" 였다. 9건 중 일부는 1차 검사에서, 나머지는 유니크 제약에서 걸렸다.
(선검사를 넣기 전과 후 모두 같은 결과로 3번씩 통과했다.)

### 확인 3: 부하 테스트

4단계-1과 같은 시나리오(500 / 2,000 / 5,000 VU, 매 실행 전 `reset.sql`, 실행 후 `verify.sql`)로 측정했다.
설정은 4단계-1에서 반영한 그대로(풀 50, accept-count 1000)이며 코드만 바뀌었다.
5,000 VU 는 편차가 커서 3번 측정했다. 이번에는 추가로 다음을 함께 기록했다.

- MySQL `Innodb_row_lock_waits` / `Innodb_row_lock_time` 의 실행 전후 차이 (행 락 대기 횟수 / 누적 대기 시간)
- MySQL `performance_schema` 로 문장 종류별 실행 횟수와 평균 시간 (실행 전 초기화)
- 실행 3초 시점의 jstack 스레드 덤프 (HTTP 스레드 200개가 무엇을 하는지)
- `typeperf` 로 1초마다 PC 전체 CPU 사용률

코드 변경은 두 단계로 나눠 측정했다.

- **① 조건부 UPDATE 로 합치기만** — 위에서 설명한 본래 변경
- **② ① + 정원 선검사** — "주의할 점 2"의 문제를 해결하기 위해, 락 없이 읽은 강의의 `isFull()` 이 참이면 UPDATE 를 보내지 않고 바로 거절

#### 5,000 VU 비교 (4단계-1 풀 50 vs ① vs ②)

| 지표 | 4단계-1 풀 50 (1회) | ① UPDATE 합치기 (4회 범위) | ② ① + 정원 선검사 (3회 범위) |
|---|---|---|---|
| 평균 응답 | 2,014 ms | 1,188 ~ 2,986 ms | 1,784 ~ 2,068 ms |
| p95 응답 | 4,407 ms | 3,171 ~ 9,134 ms | 3,419 ~ 5,112 ms |
| 초당 처리 | 734 /s | 425 ~ 927 /s | 663 ~ 985 /s |
| 오류 비율 | 39.2% | 38.0 ~ 51.6% | 27.6 ~ 38.0% |
| 연결 거절 | 2,814 | 2,754 ~ 3,405 | 2,166 ~ 2,756 |
| 신청 p95 | 4,637 ms | 2,490 ~ 9,350 ms | 3,494 ~ 4,001 ms |
| 목록 조회 p95 | 3,358 ms | 2,923 ~ 5,440 ms | 2,237 ~ 5,145 ms |
| **행 락 대기 횟수** | **1,719** | **940 ~ 1,392** | **226 ~ 234** |
| **행 락 누적 대기 시간** | **126.8 초** | **55.6 ~ 181.4 초** | **35.4 ~ 64.7 초** |
| 정합성 | 정상 | 정상 (4회 모두) | 정상 (3회 모두) |

② 의 5,000 VU 실행별 상세:

| 실행 | 평균 | p95 | 초당 처리 | 오류 | 연결 거절 | 신청 p95 | 목록 p95 | 락 대기 횟수 | 락 누적 | CPU 평균 |
|---|---|---|---|---|---|---|---|---|---|---|
| a | 1,821 ms | 5,112 ms | 663 /s | 38.0% | 2,756 | 3,679 ms | 5,145 ms | 226 | 64.7 초 | 94% |
| b | 2,068 ms | 4,003 ms | 802 /s | 29.6% | 2,282 | 4,001 ms | 4,008 ms | 229 | 49.3 초 | 93% |
| c | 1,784 ms | 3,419 ms | 985 /s | 27.6% | 2,166 | 3,494 ms | 2,237 ms | 234 | 35.4 초 | 88% |

① 의 5,000 VU 실행별 상세 (4회):

| 실행 | 평균 | p95 | 초당 처리 | 오류 | 연결 거절 | 신청 p95 | 락 대기 횟수 | 락 누적 |
|---|---|---|---|---|---|---|---|---|
| a | 2,986 ms | 9,134 ms | 425 /s | 39.0% | 2,808 | 9,350 ms | 1,194 | 181.4 초 |
| b | 1,188 ms | 3,662 ms | 791 /s | 51.6% | 3,405 | 2,490 ms | 940 | 68.3 초 |
| c | 1,772 ms | 4,860 ms | 722 /s | 41.8% | 2,950 | 4,940 ms | 1,155 | 76.4 초 |
| d | 1,446 ms | 3,171 ms | 927 /s | 38.0% | 2,754 | 2,984 ms | 1,392 | 55.6 초 |

편차에 대해: 연결 거절이 많은 실행일수록 서버에 도달하는 요청이 적어 응답 시간이 좋게 나온다 (① b: 거절 3,405건, p95 3.7초 / ① a: 거절 2,808건, p95 9.1초).
즉 오류 비율과 p95 는 서로 맞물려 움직이므로 한 번의 p95 로 우열을 가리면 안 된다. 범위로 보면 ② 는 4단계-1과 비슷하거나 약간 나은 수준이고, 확실하게 달라진 것은 락 지표다.

#### 모든 VU 단계 (p95 / 오류 비율)

| VU | 4단계-1 풀 50 | ① UPDATE 합치기 | ② ① + 정원 선검사 |
|---|---|---|---|
| 500 | 2,434 ms / 0.0% | 2,388 ms / 0.0% | 1,710 ms / 0.0% |
| 2,000 | 3,452 ms / 23.3% | 5,090 ms / 19.3% | 3,299 ms / 17.7% |
| 5,000 | 4,407 ms / 39.2% | 3,171 ~ 9,134 ms / 38.0 ~ 51.6% | 3,419 ~ 5,112 ms / 27.6 ~ 38.0% |

500 / 2,000 VU 의 행 락 지표: ① 500 VU 299회 / 60.1초, 2,000 VU 729회 / 83.3초 → ② 500 VU 220회 / 48.5초, 2,000 VU 253회 / 68.2초.
(서버를 막 띄운 직후의 첫 500 VU 실행은 JIT 예열 전이라 p95 2.6 ~ 2.9초로 나왔고, 표에는 두 번째 실행을 적었다.)

#### DB 정합성

①, ② 합쳐 12번의 부하 실행 모두 `over_capacity = 0`, `mismatch = 0`, 총 신청 행 151 이었다.

#### 서버 내부 지표 (5,000 VU)

문장 종류별 실행 횟수와 평균 시간 (performance_schema, 실행 1회분):

| 문장 | ① (실행 d) | ② (실행 a) |
|---|---|---|
| SELECT | 8,994회, 0.49 ms | 8,981회, 0.25 ms |
| **UPDATE courses (조건부)** | **2,246회, 26.1 ms (합계 58.6초)** | **239회, 271 ms (합계 64.9초)** |
| INSERT enrollments | 151회, 1.6 ms | 151회, 0.45 ms |
| COMMIT | 2,397회, 1.8 ms | 2,395회, 2.5 ms |
| ROLLBACK | 2,095회 | 2,093회 |

UPDATE 의 합계 시간(58.6초 / 64.9초)은 같은 실행의 행 락 누적 대기 시간(55.6초 / 64.7초)과 거의 같다.
즉 **UPDATE 에 걸린 시간은 사실상 전부 락을 기다린 시간**이다.

실행 3초 시점의 HTTP 스레드 200개 (jstack):

| 설정 | DB 커넥션 대기 (`HikariPool.getConnection`) | DB 작업 중 (커넥션을 쥐고 MySQL 응답 대기) |
|---|---|---|
| 4단계-1 풀 50 | 149 | 49 |
| ② 실행 a / b / c | 150 / 150 / 150 | 48 / 50 / 50 |

CPU: ② 의 5,000 VU 실행 중 PC 전체 CPU 평균 88 ~ 94%, 최고 100% (6코어). 2,000 VU 에서도 92%.

낮은 부하(50 VU, 참고용)에서는 평균 79 ms, p95 147 ms, 신청 평균 106 ms 였다. 같은 서버가 5,000 VU 에서는 20 ~ 30배 느려진다.

### 왜 ① 만으로는 효과가 없었고 ② 는 효과가 있었나

**① 의 효과가 작았던 이유**

- 락 안의 문장이 4개에서 3개로 줄었을 뿐이다. INSERT 는 외래 키 때문에 UPDATE 뒤 같은 트랜잭션에 있어야 하고(주의할 점 1), COMMIT 도 락 안에 있다.
- 정원이 찬 강의에 대한 UPDATE 도 행을 잠근다(주의할 점 2). 실행 d 에서 UPDATE 2,246회 중 실제로 신청에 성공한 것은 151회이고,
  나머지 **2,095회는 "정원이 찼다"는 답을 듣기 위해 락 줄에 섰다가 롤백**했다. 2단계의 FOR UPDATE 도 똑같이 락을 잡고 거절했으므로, 이 부분은 달라진 게 없었다.
- 그래서 락 대기 횟수가 1,719 → 940 ~ 1,392 로 조금 줄고, 누적 시간은 실행에 따라 더 늘기도 했다.

**② 의 효과**

- 락 없이 읽은 `enrolled_count` 가 이미 정원과 같으면 UPDATE 를 보내지 않는다. 이 값은 조금 낡았을 수 있지만, 정원이 찬 강의는 누가 취소하기 전까지 계속 차 있으므로
  거절해도 틀리지 않는다. 혹시 그 사이에 자리가 났더라도 다음 요청이 가져가므로 정원이 비는 일은 없고, 반대로 낡은 값이 "안 찼음"이면 UPDATE 가 최종 판단을 한다.
- UPDATE 횟수가 2,246 → 239 로, 행 락 대기 횟수가 1,719(4단계-1) → 226 ~ 234 로 **87% 줄었다.** 누적 대기 시간도 126.8초 → 35 ~ 65초로 줄었다.
- 신청 p95 가 4,637 ms → 3,494 ~ 4,001 ms 로 나아졌고, 오류 비율도 범위 기준으로 조금 내려갔다.

**그래도 락 대기가 남는 이유** — ② 에서 UPDATE 1회당 평균 대기가 140 ~ 270 ms 다. 출발 직후 10개 강의마다 20여 건이 동시에 UPDATE 에 도착해 한 줄로 서기 때문에,
줄의 길이(약 24 ÷ 2 = 12 명분) × 한 건의 락 보유 시간으로 나뉘어 **한 트랜잭션이 락을 쥐는 시간은 약 20 ms** 로 추정된다. 50 VU 에서도 같은 계산(5명 줄, 평균 대기 49 ms)으로 약 20 ms 가 나온다.
이 20 ms 안에서 측정된 것은 COMMIT 이다. 세션 전체 누적으로 COMMIT 은 평균 3.7 ms 지만 **p95 28.8 ms, p99 69 ms, 최대 349 ms** 였다 (INSERT 는 p95 3.6 ms).
COMMIT 은 로그를 디스크에 쓰는 작업(`innodb_flush_log_at_trx_commit = 1`)이고, MySQL 이 Docker Desktop 의 가상 디스크 위에서 돌기 때문에 느리고 들쭉날쭉하다.
느린 COMMIT 하나가 그 강의 줄 전체를 멈춰 세운다 (행 락 최장 대기 1,773 ms).

### 목표(5,000 VU 오류 1% 미만, p95 3초 이내) → 달성하지 못함. 남은 병목과 근거

1. **Windows 접속 대기열 200 제한 (오류의 전부)** — 오류는 ①, ② 합쳐 12번 모두 `dial: connection refused` 뿐이다.
   애플리케이션 오류(5xx) 0건, 서버 로그 예외 0건, k6 의 `enroll_other` 0건. 4단계-1에서 확인한 대로 OS 가 수락 대기열을 200 으로 자르기 때문이며,
   코드나 설정으로는 넘을 수 없다. 오류 1% 미만은 Linux(Docker 컨테이너 등)에서 서버를 띄우거나, 앞에 리버스 프록시를 두거나, 접속을 몇 초에 걸쳐 분산해야 가능하다.
2. **PC 의 CPU 포화 (응답 시간의 상한)** — 5,000 VU 동안 CPU 88 ~ 94%, 2,000 VU 에서도 92%. 서버·MySQL·k6 가 한 PC 를 나눠 쓴다.
   락 대기를 87% 줄였는데도 p95 가 3.4 ~ 5.1초인 이유다. 락과 무관한 목록 조회(단순 SELECT, 서버 쪽 0.25 ms)조차 p95 2.2 ~ 5.1초가 걸린다.
   HTTP 스레드 200개 중 150개가 커넥션을 기다리는 것도 결국 "커넥션을 쥔 50개가 CPU 를 못 받아 느리게 끝나기" 때문이다. 풀을 더 키워도 CPU 가 없으면 소용없다(4단계-1의 풀 30 결과와 같은 이유).
   정확한 서버 한계는 k6 를 다른 머신에서 돌려야 보인다.
3. **COMMIT 의 디스크 쓰기 (남은 락 보유 시간의 주범)** — 위에서 본 대로 COMMIT p95 29 ms, 최대 349 ms. 같은 강의의 신청은 한 줄로 서므로 COMMIT 이 느리면 줄 전체가 기다린다.
   `innodb_flush_log_at_trx_commit = 2` 로 바꾸면 커밋마다 디스크에 쓰지 않아 빨라지지만, MySQL 이 죽으면 최근 1초의 커밋을 잃을 수 있는 트레이드오프가 있다.
   또는 MySQL 을 가상 디스크가 아닌 환경에서 띄우는 방법이 있다. 다만 이 병목은 신청 요청에만 영향을 주고(신청 p95 3.5 ~ 4.0초), 목록 조회의 느림은 설명하지 못한다.
4. **남은 락 경합 자체는 작다** — ② 에서 대기 226 ~ 234회. 더 줄이려면 외래 키를 없애 INSERT 를 락 밖으로 빼거나(락 안 문장 3 → 2), 강의별 요청을 큐로 직렬화하는 구조 변경이 필요한데,
   1 ~ 3번이 해결되기 전에는 체감 효과가 없을 것이다.

### 반영한 코드

- `CourseRepository`: `findByIdForUpdate()` 삭제, `increaseEnrolledCountIfNotFull()` / `decreaseEnrolledCount()` 추가
- `EnrollmentRepository`: `deleteByIdReturningCount()` 추가
- `Enrollment`: `(student_id, course_id)` 유니크 제약
- `EnrollmentService`: `enroll()` 은 ② 방식(1차 검사 → 정원 선검사 → 조건부 UPDATE → INSERT, 유니크 위반은 `EnrollmentException` 으로), `cancel()` 은 DELETE → 조건부 UPDATE
- `EnrollmentConcurrencyTest`: 같은 학생 10번 동시 신청 테스트 추가
