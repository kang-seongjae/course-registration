package com.example.courseregistration.service;

import com.example.courseregistration.dto.CourseResponse;
import com.example.courseregistration.repository.CourseRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 강의 목록을 짧은 시간(1초) 동안 애플리케이션 메모리에 캐시한다. (4단계-4)
 *
 * 강의 목록은 모든 사용자에게 같은 10줄이고, 1초에 수백 ~ 수천 번 요청된다.
 * 매번 DB 에서 읽는 대신 1초에 한 번만 읽고 나머지 요청에는 같은 결과를 돌려준다.
 *
 * - 캐시된 값은 최대 1초 낡을 수 있다. 화면의 "신청 인원"이 그만큼 늦게 보일 수 있다.
 * - 신청의 최종 판단(정원 검사 + 인원 증가)은 이 캐시가 아니라 DB 의 조건부 UPDATE 가 한다.
 *   캐시는 "이미 꽉 찬 강의에 대한 요청을 DB 까지 보내지 않고 바로 거절"하는 데만 쓴다.
 * - 갱신 중에도 다른 요청을 세워 두지 않는다. 한 스레드만 DB 를 읽고, 그동안 나머지는
 *   직전 값(조금 낡은 값)을 그대로 쓴다. 200개 스레드가 한 번의 DB 읽기를 기다리며 멈추는 일을 막기 위해서다.
 * - invalidate() 는 "다음 요청이 새로 읽게 하라"는 표시만 남긴다. (취소로 자리가 났거나, DB 가 정원이 찼다고 알려준 경우)
 */
@Component
public class CourseListCache {

    /** 캐시 유효 시간 */
    private static final long TTL_NANOS = 1_000_000_000L; // 1초

    /** 한 번 읽어 둔 결과. 목록과 ID 별 조회용 맵, 읽은 시각을 함께 보관한다. */
    private record Snapshot(List<CourseResponse> list, Map<Long, CourseResponse> byId, long loadedAtNanos) {
        boolean isFresh() {
            return System.nanoTime() - loadedAtNanos < TTL_NANOS;
        }
    }

    private final CourseRepository courseRepository;

    /** 갱신은 한 번에 한 스레드만 한다 */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** volatile: 한 스레드가 바꾼 값을 다른 스레드가 바로 보게 한다 */
    private volatile Snapshot snapshot;

    /** true 면 TTL 과 상관없이 다음 요청이 새로 읽는다 */
    private volatile boolean stale;

    public CourseListCache(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    /** 강의 목록 (최대 1초 전 값) */
    public List<CourseResponse> getAll() {
        return current().list();
    }

    /** 강의 한 건 (최대 1초 전 값). 없는 ID 면 비어 있다. */
    public Optional<CourseResponse> get(Long courseId) {
        return Optional.ofNullable(current().byId().get(courseId));
    }

    /** 다음 요청이 DB 에서 새로 읽도록 표시한다. 갱신이 끝날 때까지는 직전 값이 계속 쓰인다. */
    public void invalidate() {
        stale = true;
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s != null && !stale && s.isFresh()) {
            return s; // 대부분의 요청은 여기서 끝난다 (DB 도, 커넥션도, 락도 쓰지 않는다)
        }

        // 갱신이 필요하다. 갱신 자리를 잡은 스레드만 DB 를 읽는다.
        if (refreshLock.tryLock()) {
            try {
                s = snapshot;
                if (s != null && !stale && s.isFresh()) {
                    return s; // 자리를 잡는 사이 다른 스레드가 이미 갱신했다
                }
                stale = false; // 읽기 시작 전에 내린다. 읽는 도중에 invalidate() 가 오면 다음 요청이 다시 읽는다.
                s = load();
                snapshot = s;
                return s;
            } finally {
                refreshLock.unlock();
            }
        }

        // 다른 스레드가 갱신 중이다. 직전 값이 있으면 (조금 낡았더라도) 기다리지 않고 그것을 쓴다.
        if (s != null) {
            return s;
        }

        // 앱 시작 직후라 아직 아무 값도 없다. 이때만 갱신이 끝날 때까지 기다린다.
        refreshLock.lock();
        try {
            s = snapshot;
            if (s == null) {
                s = load();
                snapshot = s;
            }
            return s;
        } finally {
            refreshLock.unlock();
        }
    }

    /** DB 에서 강의 목록을 읽어 스냅샷을 만든다 (SELECT 한 번) */
    private Snapshot load() {
        List<CourseResponse> list = courseRepository.findAll().stream()
                .map(CourseResponse::from)
                .toList();
        Map<Long, CourseResponse> byId = list.stream()
                .collect(Collectors.toMap(CourseResponse::id, Function.identity()));
        return new Snapshot(list, byId, System.nanoTime());
    }
}
