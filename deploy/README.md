# 운영 배포 준비

추가 운영 경계 보안·요청 제한·읽기 전용 웹 컨테이너·인증서 무중단 갱신·점검 명령은 [운영 보안 계약](../docs/contracts/production-security.md)을 따른다. 새 배포 설정에는 공개 ACME challenge를 둘 절대 경로 `ACME_DIRECTORY`도 필요하다. 비밀 파일을 이 디렉터리에 두지 않는다.

이 패키지는 기존 문서의 **서울 EC2 1대 · Ubuntu 24.04 · Nginx/Java Docker Compose · 비공개 RDS PostgreSQL 17** 구성이다. 2026-11-22 `https://jibro.live`에 최초 수동 배포했으며 Linux 컨테이너·운영 RDS TLS·공개 HTTPS 통신을 확인했다. 실제 적용 범위와 남은 검증은 [배포 기록](../docs/history/entries/2026-11-22-aws-free-deployment.md)을 따른다. 로컬 코드와 운영 산출물은 이후 자동으로 동기화되지 않는다.

## 빌드와 산출물

- 프런트: `frontend`에서 `npm ci`, `npm run ci` 후 `dist/`.
- 서버: Java 17에서 `backend/gradlew -p backend test integrationTest bootJar`. 통합 검사는 별도 PostgreSQL 테스트 DB를 사용한다. `build/libs`의 실행 jar는 문서 파서 worker도 포함해야 한다.
- 저장소 루트: `python scripts/ops/package_release.py --output .tools/releases/<고유-버전>`.
- `manifest.json`에는 파일별 SHA-256, 소스 HEAD, 미커밋 변경 유무가 기록된다. 패키징은 테스트나 배포 성공 판정이 아니다. `.env`, DB, 로그, 개인 자료는 패키징 대상이 아니다.
- GitHub의 `Release package` 수동 workflow는 검사 후 프런트/서버 산출물과 패키지를 만든다. 원격 레지스트리 push나 서버 적용은 수행하지 않는다.

압축을 푼 패키지 루트에서 이미지를 각각 빌드한다. 이미지 이름/태그는 운영자가 사용할 레지스트리와 적용 버전으로 지정한다. 베이스 이미지의 승인된 digest를 함께 기록하여 재현성을 확보한다.

```sh
docker build --build-arg JAVA_BASE='<승인된 Temurin 17 JRE 이미지@sha256:digest>' -f deploy/backend.Dockerfile -t <backend-release-tag> .
docker build --build-arg NGINX_BASE='<승인된 nginx 1.30.6 이미지@sha256:digest>' -f deploy/web.Dockerfile -t <web-release-tag> .
```

운영 `.env`는 빌드된 두 이미지의 **immutable digest**를 사용한다. 프런트와 서버를 독립적으로 갱신할 수 있다. 로컬 이미지 태그만으로 운영 검증을 통과시키지 않는다. 현재 컨테이너 메모리 상한은 서버 1.5 GiB + Nginx 128 MiB이고, 파서 자식 프로세스와 OS 여유가 추가로 필요하므로 1 GiB EC2에서 실행 가능하다고 가정하지 않는다. 실제 사양과 비용은 계정 조건 확인 후 지정한다.

## 최초 설정

1. RDS는 외부 공개하지 않고 5432 인바운드를 EC2 보안 그룹으로 제한한다. EC2는 80/443만 공개하고 8080을 외부에 열지 않는다. 관리 접속은 운영자 접근 경로로 제한한다.
2. `deploy/.env.example`을 `.env`, `runtime.env.example`을 `runtime.env`로 복사하여 실제 값으로 채운다. 서버 디렉터리는 운영자만, 파일은 `chmod 600`으로 제한한다. 값은 Git/명령행/공유 로그에 남기지 않는다. `runtime.env`는 Compose의 raw 형식이므로 값을 따옴표로 감싸지 않는다.
3. `TLS_DIRECTORY`에 도메인에 유효한 `fullchain.pem`/`privkey.pem`, `RDS_CA_FILE`에 [공식 RDS CA](https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem)를 배치한다. DB는 `sslmode=verify-full`과 해당 CA로 검증한다. 인증서 발급·갱신은 실제 도메인 확인 후 구성하며, 갱신 후 `nginx -t` → `nginx -s reload`로 적용한다.
4. `FRONTEND_URL=https://실제도메인`과 `PUBLIC_HOST=실제도메인`을 일치시킨다. 운영 프로필은 HTTPS·Secure 쿠키·검증된 DB TLS를 검사하며 `local-sample` 혼용을 거부한다. 시연 계정/샘플 공고를 운영 DB로 가져오지 않는다.
5. 다음 검사를 실행한다. 값 전체를 출력하는 `docker compose config` 대신 `--quiet`를 사용한다.

```sh
python scripts/ops/check_configuration.py --env deploy/runtime.env --deployment deploy/.env
docker compose --env-file deploy/.env -f deploy/compose.production.yaml config --quiet
```

Compose **2.30 이상**이 필요하다(raw env_file). 서버는 환경 변수를 상속한 문서 파서 프로세스를 만들지 않는다. worker는 작업별로 실행·종료하며 파서 임시 디렉터리는 `/tmp`에만 쓴다.

## 외부 기능 연결 확인

사이트 첫 접속 사람 확인은 [Turnstile 연결 계약](../docs/contracts/visitor-verification.md)을 따른다. backend 전용 `runtime.env`의 `TURNSTILE_ENABLED/SITE_KEY/SECRET_KEY`로 연결하며 비밀키는 웹 이미지에 넣지 않는다. 초기값 false이며 실제 키와 hostname을 연결·검증한 뒤 true로 전환한다.

| 기능 | 설정 | 실제 완료 판단 |
| --- | --- | --- |
| LH 수집 | `LH_SERVICE_KEY`(Decoding 키) | 관리자 즉시 수집의 성공·페이지·중복, 예약 슬롯 00/06/09~18시 실행과 원본 이력 확인. 키만 있는 상태는 미검증이다. |
| 메일 | `SPRING_MAIL_*`, `JIBRO_MAIL_FROM` | 본인 테스트 주소에서 이메일 확인/재설정·알림 수신 확인. SMTP 서버 수락과 받은편지함 도착을 구분한다. |
| Web Push | P-256 개인 `JIBRO_VAPID_JWK`, 운영자 `JIBRO_PUSH_SUBJECT` | HTTPS 브라우저의 사용자 허용·구독·실제 수신과 구독 해제/만료 처리 확인. 키 생성만으로 수신 검증을 대신하지 않는다. |
| Google·카카오 | 각 CLIENT_ID/CLIENT_SECRET | `https://도메인/login/oauth2/code/google` 또는 `kakao` 등록 후 본인 로그인·연결·해제·로그아웃 확인. 카카오 필요 시 서버 호출 허용 IP 확인. |

설정 확인 도구는 존재/형식만 보고하고 네트워크 요청이나 발송을 하지 않는다. 외부 키를 제공하기 전에는 키 없음이 정상적인 비활성 상태다. 미저장 확인의 요청/응답 본문과 민감 답변을 프록시·오류 수집 도구에 기록하지 않는다.

2026-11-07 연결 설정 보완:

- `LH_COLLECTION_CATEGORY_CODES=06,13`, `LH_COLLECTION_HISTORY_START_DATE=2000-01-01`은 수집 분류와 게시일 하한을 명시한다. 2000년은 초기 설정값이며 LH가 그 시점까지 자료를 제공한다는 뜻이 아니다. 실제 응답 범위·호출 한도·분류별 전체 페이지는 승인된 키로 확인한다. 범위와 이어읽기 위치는 실행 기록에 보존된다.
- `OAUTH_BASE_URL`을 비워 두면 운영 `FRONTEND_URL`을 사용한다. 운영에서는 둘의 공개 HTTPS origin이 같아야 한다. Google·카카오 콘솔의 callback은 이 주소의 `/login/oauth2/code/{provider}`와 정확히 맞춘다. 요청 Host 헤더로 복귀 주소를 결정하지 않는다.
- 푸시 등록 요청은 작업을 시작한 계정의 `expectedUserId`를 포함한다. 프런트와 서버를 호환하는 버전으로 함께 준비한다. 이메일·푸시는 채널 설정뿐 아니라 현재 동의·이메일 확인·기기 소유권을 전송 직전에 확인한다. 외부 서버 수락을 실제 사용자 수신으로 기록하지 않는다.

## 수동 적용과 되돌리기

1. 적용 전 현재 두 이미지 digest·Flyway 버전·DB 백업 및 복구 확인 결과를 기록한다. 프런트/서버 호환 조합과 마이그레이션을 먼저 검토한다.
2. 서버 변경은 `docker compose --env-file deploy/.env -f deploy/compose.production.yaml up -d --no-deps backend`로 적용한 뒤 health가 UP인지 확인한다. V18/V19는 추가 테이블/컬럼 방식이다. V20은 수집 범위·응답 이력에 필수 컬럼을 추가하므로 이를 쓰지 않는 구버전 서버로 복귀하면 수집 INSERT가 실패한다. V21은 접수 시각 조회용 컬럼·인덱스를 추가하고 기존 원고에서 값을 보완한다. 현재 DB에 호환하는 서버 버전을 확인하기 전에는 자동 복귀하지 않는다.
3. 프런트 변경은 같은 명령의 마지막을 `web`으로 바꿔 독립 적용한다. 최초 전체 기동은 `up -d`로 두 서비스를 시작한다. Nginx upstream은 Docker DNS(127.0.0.11)를 10초 주기로 갱신하므로 서버 컨테이너만 교체하여 IP가 바뀌어도 이전 주소에 고정되지 않는다. 갱신 지연 중의 일시 오류와 적용 후 health를 확인한다.
4. HTTPS `/api/v1/health`, `/notices` 직접 새로고침, 로그인·CSRF·Secure 쿠키·로그아웃, 공식 종료 표시, 이번만 확인 후 개인 DB 불변을 확인한다. 브라우저 OAuth 복귀 주소가 HTTPS 공개 주소인지 확인한다.
5. 프런트만 실패하면 이전 `WEB_IMAGE`로 복귀한다. 서버 복귀는 현재 DB와 호환하는 이전 `BACKEND_IMAGE`에만 적용한다. DB를 자동으로 down migration 하거나 기존 DB 위에 덤프를 덮어쓰지 않는다.
6. 데이터 복구가 필요하면 쓰기를 멈추고 RDS를 **새 인스턴스**로 복구한다. 기록·소유권·Flyway/버전 확인 후 새 접속 주소로 전환한다. 복구 시점 이후 변경·삭제 요청 재반영과 손실 범위를 기록한다.

배포 후 공개 HTTPS 주소에서 아래 읽기 전용 검사를 실행한다. 인증서 검증을 끄는 옵션은 없으며, 성공해도 로그인·외부 발송 확인을 대신하지 않는다.

```sh
python scripts/ops/smoke_web.py --origin https://실제도메인
```

## 공개 안내·방문 통계

문의 시간·이메일·개인정보처리방침·검색 설명은 `data/site/public-site.json`에서 관리하며 Vite 빌드가 공개 HTML·robots·sitemap을 생성한다. 이 페이지는 로그인·사람 확인 전에도 읽을 수 있다. `frontend/dist/app-routes.conf`는 실제 React 라우트에서 생성한 Nginx 허용 목록이다. 웹 이미지에 함께 넣고, 새 라우트를 별도 Nginx 목록에 중복 작성하지 않는다. 없는 일반 주소는 맞춤 오류 화면과 HTTP404를 반환한다. 실제 공고 ID 존재 여부는 앱/API에서 확인한다.

GA4를 연결하려면 `frontend/.env.production.local`에 공개 측정 ID `VITE_GA_MEASUREMENT_ID`와 신규 등록 화면에서 발급된 컨테이너 ID `VITE_GA_TAG_ID`를 설정한 뒤 빌드한다. 비밀번호·API secret은 프런트 설정에 넣지 않는다. Google의 2026-11-06 신규 설치 안내에 맞춰 발급된 GTM 태그를 로드하고, 동의와 전역 개인정보 설정을 js 초기화보다 먼저 적용한다. 측정 ID 직접 초기화를 별도 경로로 유지하지 않는다. GA 관리 화면에서 향상된 측정·광고 통합을 끄고, 사용자·이벤트 보관 2개월과 활동 시 재설정 끄기를 확인한다. ID가 없거나 로컬·시연 환경이면 분석을 보내지 않는다. 운영에서도 사용자 동의 전 태그를 불러오지 않고, 공개 화면 종류만 보내며 개인 답변·메모·검색어·공고 식별값·인증 URL을 보내지 않는다. 하단 통계 설정에서 철회할 수 있다. 태그 설치와 실제 데이터 수신은 별도로 검증한다.

문의 메일 수신은 무료 ImprovMX의 support 별칭만 기존 운영 Gmail로 전달한다. 기본 전체 주소(*) 전달은 사용하지 않는다. MX 10/20과 단일 SPF 레코드를 확인하고 기존 웹 A·Brevo 소유 TXT·DKIM·DMARC를 보존한다. ImprovMX 계정의 가입 주소와 실제 전달 받을 주소를 혼동하지 않는다. SMTP 수락만으로 완료 처리하지 않고 전달 로그의 Gmail 수신 서버 성공까지 확인한다. DNS 변경은 사이트 배포와 별개이며 유료 플랜 전환은 포함하지 않는다.

## 백업·복구

- 초기 운영안: 암호화된 RDS 자동 백업 7일 + 적용 전 수동 스냅샷. 이는 운영 기본안이며 법정 보존기간이 아니다. 실제 저장량·비용·삭제 반영 정책 확인 뒤 확정한다.
- 2026-11-22 최초 계정의 Free 플랜은 7일 설정을 거부하여 실제 적용은 **1일**이다. 플랜을 업그레이드하지 않았다. 별도 장기 백업과 실제 복구 훈련은 후속 작업이며 7일 복구가 가능하다고 안내하지 않는다.
- 운영 DB 자격과 별도로 백업/복구 담당자의 접속 권한을 사용한다. 애플리케이션 계정에 새 DB 생성/관리자 권한을 주지 않는다.
- 논리 백업 도구는 PostgreSQL 17 클라이언트와 `PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSFILE`을 사용한다. 백업 파일은 개인정보를 포함하므로 접근 제한·암호화된 디스크에 두고 Git/릴리스에 넣지 않는다.
- 원격 백업·복구 검증은 명시적 `PGHOST`, `PGSSLMODE=verify-full`, 실제로 읽을 수 있는 비어 있지 않은 `PGSSLROOTCERT` 파일이 필수다. `PGSSLROOTCERT`는 백업 도구를 실행하는 호스트의 신뢰할 수 있는 RDS CA 파일 경로이며, 컨테이너 내부 JDBC 경로와 다를 수 있다. 암호는 `PGPASSFILE`로 제공한다. `PGSERVICE/PGSERVICEFILE` 및 Unix socket·다중 호스트 설정은 숨은 연결 설정으로 검증을 우회하지 못하도록 거부한다. 검사 실패 시 PostgreSQL 명령을 실행하지 않는다.

```sh
python scripts/ops/database_backup.py backup --output /보호된경로/<고유시각>.dump
python scripts/ops/database_backup.py restore-check --archive /보호된경로/<고유시각>.dump
```

개발 컴퓨터의 복구 실습만 각 명령에 `--allow-local`을 명시할 수 있다. 이 예외는 `PGHOST`가 문자 그대로 `localhost`, `127.0.0.1`, `::1` 중 하나일 때만 허용하며, `PGHOSTADDR`가 있으면 단일 loopback IP여야 한다. 원격 호스트에는 이 옵션을 사용할 수 없다. 로컬에서도 서비스 파일 설정은 허용하지 않는다. 예: `python scripts/ops/database_backup.py restore-check --allow-local --archive /보호된경로/<고유시각>.dump`.

복구 확인은 SHA-256을 먼저 대조하고 매번 `jibro_restore_check_<난수>`라는 새 DB만 만든다. 복구·제약조건·테이블 수를 확인한 뒤 그 임시 DB만 지운다. 기존 DB를 복구 대상으로 지정하는 옵션은 없다. 소스 DB에는 쓰지 않는다. 다른 검증용 PostgreSQL 서버의 접속 환경을 사용해도 된다. 파일은 자동 삭제하지 않으며 보관 책임자가 정한 기간에 삭제한다.

운영에서는 정기 복구 실습과 RDS 시점 복구 소요 시간을 측정해 RPO/RTO를 정한다. 계정/준비 영구 삭제 시 기존 백업이 즉시 바뀌지는 않는다. 백업 만료/복구 후 삭제 재반영 절차까지 운영 정책에 포함한다. 현재 코드만으로 이 운영 절차가 자동 수행된다고 안내하지 않는다.

## 관찰·기록

컨테이너 재시작 정책, healthcheck, JSON 로그 10 MiB × 3개 회전을 적용한다. Nginx는 API/OAuth 접근 로그를 끄고 일반 접근 로그에서도 query string을 제외한다. upstream 오류의 error log에는 원래 요청 주소가 붙을 수 있으므로 두 가상 서버의 요청 오류 로그는 버린다. OAuth code·state 등 query가 로그에 남지 않게 하기 위한 결정이다. 기동·설정 오류와 컨테이너 health, 비밀값을 포함하지 않는 서버 로그로 장애를 확인하며 원문 요청 로그를 다시 켜지 않는다. 컨테이너/DB 상태·예약 수집 실패는 운영자가 확인한다. 외부 장애 알림 수신처와 담당자는 실제 운영 설정 때 정한다. 비밀값 전체를 출력하는 설정 검사나 요청 본문 로깅은 사용하지 않는다.

공식 근거: [Compose 운영](https://docs.docker.com/compose/how-tos/production/), [env_file raw](https://docs.docker.com/reference/compose-file/services/#format), [Nginx 프록시 헤더](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header), [Spring 프록시 처리](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server), [PostgreSQL 백업](https://www.postgresql.org/docs/17/backup-dump.html).

### 레지스트리 없이 서버에서 빌드하는 경우

검증한 배포 패키지를 서버로 전송해 빌드한 경우 `docker image inspect --format '{{.Id}}' <release-tag>`의 전체 `sha256:...` 이미지 ID도 사용할 수 있다. Compose는 서버에 존재하는 해당 이미지를 사용한다. 이동 가능한 레지스트리 digest와 달리 새 서버에는 같은 이미지를 별도로 전송해야 한다. 변경 가능한 태그나 짧은 ID는 운영 검사에서 계속 거부한다.

백엔드 이미지에는 한국어·영어 Tesseract OCR과 언어 자료가 포함된다. 배포 때 실행 파일과 언어 자료가 실제로 읽히는지 컨테이너에서 확인한다.
