// k6 부하 테스트 스크립트
// 시나리오: 가상 사용자(VU)마다 서로 다른 학생 ID로
//   1) 강의 목록을 조회하고
//   2) 강의 하나를 골라 신청한다.
//
// 실행 예: k6 run -e VUS=500 load-test/enroll.js
//   VUS      : 가상 사용자 수 (기본 500). 학생 ID 는 1 ~ VUS 를 사용한다.
//   BASE_URL : 서버 주소 (기본 http://localhost:8080)

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const VUS = Number(__ENV.VUS || 500);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 신청 결과를 종류별로 세는 카운터
const enrollSuccess = new Counter('enroll_success');   // 201 신청 성공
const enrollFull = new Counter('enroll_full');         // 400 정원 초과 (정상 응답으로 간주)
const enrollOther = new Counter('enroll_other');       // 그 밖의 응답 (진짜 오류)
const listError = new Counter('list_error');           // 목록 조회 실패 (연결 오류 등)

// 어떤 종류의 오류인지 알 수 있도록 VU 당 첫 1건만 메시지를 출력한다
let loggedErrors = 0;
function logError(label, res) {
  if (loggedErrors < 1) {
    loggedErrors++;
    console.error(`${label} 실패: status=${res.status} error_code=${res.error_code} error=${res.error}`);
  }
}

export const options = {
  scenarios: {
    enroll: {
      executor: 'per-vu-iterations',  // VU 마다 정해진 횟수만 실행
      vus: VUS,
      iterations: 1,                  // 각 학생은 딱 한 번 신청한다
      maxDuration: '10m',
    },
  },
  // 임계값(thresholds)에 적어 두면 요약 결과에 요청 종류별 지표가 따로 나온다.
  // 값은 충분히 커서 실패하지 않는다. (측정이 목적이므로)
  thresholds: {
    'http_req_duration{name:list}': ['p(95)<600000'],
    'http_req_duration{name:enroll}': ['p(95)<600000'],
    'http_req_failed': ['rate<1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

// 정원 초과 거절(400)은 오류가 아니라 정상 응답으로 집계한다
const enrollExpected = http.expectedStatuses(201, 400);

export default function () {
  const studentId = __VU; // VU 번호(1부터)를 학생 ID 로 사용 → 모두 다른 학생

  // 1) 강의 목록 조회
  const listRes = http.get(`${BASE_URL}/api/courses`, { tags: { name: 'list' } });
  check(listRes, { '목록 조회 200': (r) => r.status === 200 });
  if (listRes.status !== 200) {
    listError.add(1, { error_code: String(listRes.error_code) });
    logError('목록 조회', listRes);
    return;
  }

  let courses = [];
  try {
    courses = listRes.json();
  } catch (e) {
    return;
  }
  if (!courses || courses.length === 0) return;

  // 2) 강의 하나를 무작위로 골라 신청
  const course = courses[Math.floor(Math.random() * courses.length)];
  const enrollRes = http.post(
    `${BASE_URL}/api/enrollments`,
    JSON.stringify({ studentId: studentId, courseId: course.id }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'enroll' },
      responseCallback: enrollExpected,
    }
  );

  if (enrollRes.status === 201) {
    enrollSuccess.add(1);
  } else if (enrollRes.status === 400 && enrollRes.body && enrollRes.body.includes('정원')) {
    enrollFull.add(1);
  } else {
    enrollOther.add(1, { error_code: String(enrollRes.error_code) });
    logError('신청', enrollRes);
  }

  check(enrollRes, {
    '신청 결과 201 또는 정원초과 400': (r) =>
      r.status === 201 || (r.status === 400 && r.body && r.body.includes('정원')),
  });
}
