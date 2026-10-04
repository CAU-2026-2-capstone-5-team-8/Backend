# 운영 인증과 프록시 경계 (2026-10-05)

기존 웹 프록시는 접속자 주소를 보내지 않았고 백엔드는 소켓 주소당 가입/로그인 합계 1분 10회를 적용했다. 프록시를 경유한 사용자가 하나의 제한을 공유했다. 무조건 X-Forwarded-For를 신뢰하면 주소 위조로 제한을 우회할 수 있으므로 양쪽에 명시적 신뢰 경계를 둔다.

## 배포 설정

- 웹 `TRUSTED_PROXY_CIDRS`: 웹 앞 로드밸런서의 실제 소켓 CIDR만 지정한다. 직접 접속이면 비워 둔다. 오른쪽부터 신뢰한 홉을 지나 최초 비신뢰 주소를 택하고 백엔드로 보내는 X-Forwarded-For를 그 주소 하나로 덮어쓴다.
- 백엔드 `APP_AUTH_TRUSTED_PROXY_CIDRS`: 웹 서버의 실제 소켓 CIDR만 지정한다. 기본값은 비어 있어 전달 헤더를 신뢰하지 않는다. 호스트명, wildcard, `/0`은 거부한다. 넓은 사설망 전체보다 개별 주소 `/32` 또는 `/128`을 우선한다.
- `server.forward-headers-strategy=none`을 유지한다. Servlet 필터 전에 소켓 주소가 헤더로 재작성되면 신뢰 검증이 무효화될 수 있다.
- Tomcat의 `server.tomcat.remoteip.remote-ip-header`와 `protocol-header`도 비워 둔다. 이 두 설정은 `none`이어도 RemoteIpValve를 활성화하므로 운영 시작 검사에서 별도로 차단한다.
- 전달 헤더는 숫자 IP만 허용하고 최대 16홉, 2048자이다. 잘못된 헤더는 소켓 주소로 되돌린다. DNS 조회는 하지 않는다.
- 운영에서는 `SPRING_PROFILES_ACTIVE=production`, `APP_AUTH_MODE=required`, `ML_MODE=http`, 명시적 `ML_BASE_URL`을 함께 설정한다. `prod`도 보호한다. stub, 인증 우회, demo/local/test/로컬 진단 bootstrap 혼용, 자동 전달 헤더 재작성은 singleton/DB 초기화 전에 시작을 실패시킨다.
- ML 주소는 인증정보·쿼리·fragment·하위 경로가 없는 HTTP(S) origin이다. 비밀값을 저장소에 넣지 않는다.

## 제한과 동작

접속자 주소당 가입/로그인 합계 10회/60초, 정규화 이메일당 합계 10회/60초를 별도로 적용한다. 계정 제한은 이메일 SHA-256만 메모리에 저장하고 최대 10,000개 버킷이다. 제한 시 429와 Retry-After: 60을 반환한다.

현재 제한은 **프로세스별**이며 재시작하면 초기화된다. 다중 인스턴스의 전역 제한이나 대규모 공격 방어는 아니다. 운영에는 경계 바깥 공유 rate limiter/WAF, backend 직접 접근 차단, 좁은 프록시 네트워크 ACL도 필요하다. 같은 NAT 공인 주소는 IP 제한을 공유한다. 프록시 CIDR 오설정까지 자동으로 알아낼 수는 없다.

## 검증

운영 위험 설정이 시작을 허용하는 기존 동작과 프론트 주소 전달 부재를 먼저 재현했다. 테스트는 trusted/untrusted hops, 위조·잘못된 헤더, IPv6/IPv4-mapped 주소, 사용자별 제한, 계정 정규화/만료를 포함한다. HTTP/PostgreSQL 검사는 서로 다른 주소 12개가 공유 차단되지 않고 여러 IP의 동일 계정 시도가 제한되는지 확인한다.

참고: [Spring의 전달 헤더 신뢰 경계](https://docs.spring.io/spring-security/reference/7.0/features/exploits/http.html).
