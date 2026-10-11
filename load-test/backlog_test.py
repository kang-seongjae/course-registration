# 접속 대기열(listen backlog) 실험 (4단계-1, 4단계-3)
# Windows:  python load-test/backlog_test.py 5000 6000
# Linux 컨테이너: docker run --rm -v "${PWD}/load-test:/t:ro" python:3-alpine python /t/backlog_test.py 5000 6000

import socket, sys

# usage: backlog_test.py <listen backlog> <number of connect attempts>
# listen() 만 하고 accept() 는 하지 않는 서버에 연결을 계속 넣어 본다.
# 접속 대기열이 꽉 차면 그 다음 connect 는 실패(거절 또는 시간 초과)한다.
backlog = int(sys.argv[1]); n = int(sys.argv[2])
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', 9000)); srv.listen(backlog)
ok = fail = 0; clients = []; first_fail = None
for i in range(n):
    c = socket.socket(); c.settimeout(0.5)
    try:
        c.connect(('127.0.0.1', 9000)); ok += 1; clients.append(c)
    except Exception as e:
        fail += 1
        if first_fail is None: first_fail = (i + 1, type(e).__name__)
        if fail >= 20: break
try:
    somax = open('/proc/sys/net/core/somaxconn').read().strip()
except Exception:
    somax = 'n/a (Windows)'
print(f"listen({backlog}): connected={ok} failed={fail} first_failure_at={first_fail} somaxconn={somax}")
