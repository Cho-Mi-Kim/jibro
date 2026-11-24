# 운영 경계 보안과 상태 점검

2026-11-24 구현 기준. 기존 인증·CSRF·권한·사람 확인은 각 계약을 따른다. EC2 직접 접속형 Nginx의 추가 방어이며 Cloudflare WAF나 대규모 DDoS 방어 도입을 뜻하지 않는다.

## 요청 제한

`deploy/nginx/default.conf.template`은 직접 연결한 IP를 사용하고 전달 IP를 덮어쓴다. 외부 CDN을 추가한다면 신뢰할 프록시와 실제 IP 처리를 함께 검토한다.

| 범위 | IP당 보충 속도 | 순간 허용 여유 |
| --- | --- | --- |
| API·OAuth | 초당 30개 | 60개 |
| 로그인·체험·인증 완료·OAuth 시작 | 분당 20개 | 10개 |
| 가입·재설정 등 메일 요청 | 분당 2개 | 4개 |
| 사람 확인 제출 | 분당 6개 | 4개 |

순간 허용 여유는 첫 요청 외에 수용하는 수다. GET 사람 확인·CSRF·세션 갱신은 낮은 제출 제한에 포함하지 않는다. 일반 API 제한과 기존 계정별 로그인 제한은 별도로 유지한다. 초과하면 429 JSON과 `Retry-After: 30`을 반환한다. 정적 화면은 API 제한과 분리하며 공유 IP의 오탐은 관찰 근거와 함께 조정한다.

API 위치 아래에서 로그인·메일·사람 확인 경로를 나눠, 적용되는 세부 제한을 각 위치의 마지막 `limit_req`로 둔다. Nginx는 마지막 제한의 첫 요청을 직접 초기화하므로 갓 부팅한 호스트에서도 첫 요청과 여유분이 모두 허용된다. 자식 위치에 제한을 선언하면 부모 제한을 상속하지 않아 공통 API 제한도 각 위치에 명시한다. 정규식 자식 위치에는 `proxy_pass`를 선언하고 전달 헤더·연결 제한 시간은 부모에서 공유한다. 요청 메서드 판별은 기존 map이 맡는다.

동시 처리 64개/IP, 헤더 10초·본문 15초·keep-alive 30초·전송 30초를 적용한다. 허용 도메인 외 HTTP Host는 421, 알 수 없는 TLS SNI는 handshake를 거부한다. 점 파일·키/환경/백업/설정 확장자·개발 화면·검수 보고서는 서버에서 차단한다. 검수 HTML과 코드 모델은 빌드에서도 제외하되 개발 원본은 보존한다. robots.txt를 접근 제어로 간주하지 않는다.

## 브라우저 정책

`frontend/scripts/production-security.mjs`가 실제 빌드 HTML의 줄바꿈을 정규화하고 인라인 모듈 SHA-256을 수집해 `security-headers.conf`를 만든다. 임의 인라인 스크립트·eval·HTML 이벤트 속성을 허용하지 않는다. 새 정적 HTML의 실행 속성은 빌드 실패로 처리한다. 세 문서 뷰어도 정확한 스크립트 해시와 동일 출처 모듈로 실행한다.

외부 스크립트는 Cloudflare 사람 확인·Google 태그 도메인으로 한정하며 연결/iframe도 필요한 출처만 허용한다. 방문 통계 동의는 기존 코드가 계속 관리한다. React 스타일 속성 때문에 `style-src`에만 `unsafe-inline`을 허용한다. 객체·기준 URL 삽입, 외부 폼 제출, 타 사이트 프레임 삽입을 차단한다. 사용하지 않는 카메라·마이크·위치·결제·USB 권한을 비활성화하며 자원은 동일 출처 정책을 사용한다.

## 컨테이너·인증서

웹 컨테이너는 읽기 전용 파일시스템, 크기 제한 임시 디렉터리, 권한 승격 금지, 전체 capability 제거 후 Nginx에 필요한 CHOWN/SETUID/SETGID/NET_BIND_SERVICE만 사용한다. 8081 health는 컨테이너 loopback 전용이다.

`ACME_DIRECTORY`는 비밀 파일을 두지 않는 Certbot webroot다. HTTP는 `/.well-known/acme-challenge/`만 읽기 허용하고 나머지는 HTTPS로 이동한다. 운영 webroot 모의 갱신에 성공한 뒤 기존 standalone 웹 정지/시작 hook을 제거한다. `deploy/certbot-install-certificate.sh`를 root 소유 실행 파일로 설치한다. 갱신 hook은 해당 도메인·유효기간·키 일치를 확인하고 복사 → Nginx 설정 검사 → 재로드하며 실패하면 직전 인증서로 복구한다. staging 인증서를 운영에 설치하지 않는다.

## 점검·장애 대응

```sh
python scripts/ops/verify_edge.py
python scripts/ops/smoke_web.py --origin https://jibro.live
sudo python3 /opt/jibro/current/scripts/ops/production_doctor.py --origin https://jibro.live
```

첫 검사는 실제 운영 설정/이미지를 가짜 API·임시 신뢰 인증서·loopback 포트만으로 검증한다. 실제 애플리케이션 API나 메일 발송은 호출하지 않는다. CI의 edge-security에서도 같은 검사를 실행한다. CI 설정의 실제 원격 실행은 push 후 확인한다.

순간 요청 검사는 각 정책의 모든 주소가 같은 예산을 공유하는지, 끝 슬래시와 OAuth GET, 하위 프록시의 전달 IP 덮어쓰기, 제한 대상이 아닌 GET·OAuth 복귀 경로를 함께 확인한다. 허용 수를 줄이거나 재시도·대기로 한도를 회복시켜 통과시키지 않는다.

검사 전용 인증서 키는 비공개 임시 디렉터리 안에서만 만들며, 호스트 일반 사용자와 웹 컨테이너의 UID 차이에도 읽을 수 있도록 해당 폐기 키에만 읽기 권한을 준다. 운영 인증서 권한이나 컨테이너 capability를 완화하지 않는다. 기동 실패 시 정리 전에 해당 임시 컨테이너의 최근 로그를 오류에 포함한다.

운영 점검은 인증서 잔여 14일 미만, 디스크 90% 이상/여유 1GiB 미만, 컨테이너 비정상·health 누락·재시작 3회 이상, 키/환경파일의 그룹·타인 접근, 갱신/보안 업데이트 중지, 공개 경로/보안 smoke 실패를 오류 종료로 반환한다. 환경변수·개인 요청·비밀값을 출력하지 않는다. 수동 명령이며 새 상주 감시·외부 장애 알림은 설정하지 않는다.

장애 시 health와 마지막 정상 release를 확인하고 웹만 복구한다. 인증서 위험이면 webroot와 certbot 상태를 확인한다. 키 노출이 의심되면 공급자에서 해당 키를 폐기·재발급하고 서버 비공개 설정에 반영한다. 계정 탈취는 세션도 폐기한다. 원문 로그·백업을 공개 PR에 붙이지 않는다.

RDS 자동 백업은 현재 무료 플랜의 **1일** 한계다. 장기 백업·실제 재해 복구·AWS MFA·과금 한도를 이번 보완으로 완료했다고 주장하지 않는다. 기존 백업/복원 절차는 `deploy/README.md`를 따른다.

근거: [Nginx 요청 제한](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html), [Turnstile CSP](https://developers.cloudflare.com/turnstile/reference/content-security-policy/), [Certbot 갱신](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates), [OWASP 보안 헤더](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html).
