-- 부하 테스트 후 데이터 정합성을 확인한다.
--   over_capacity : 신청 인원이 정원을 넘은 강의 수 (0 이어야 함)
--   mismatch      : enrolled_count 와 실제 신청 내역 행 수가 다른 강의 수 (0 이어야 함)
-- 실행: docker exec -i course-registration-mysql mysql -uapp -papp1234 course_registration < load-test/verify.sql
select c.code,
       c.capacity,
       c.enrolled_count,
       (select count(*) from enrollments e where e.course_id = c.id) as actual_rows
from courses c
order by c.id;

select sum(c.enrolled_count > c.capacity) as over_capacity,
       sum(c.enrolled_count <> (select count(*) from enrollments e where e.course_id = c.id)) as mismatch,
       (select count(*) from enrollments) as total_rows
from courses c;
