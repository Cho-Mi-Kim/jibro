# 초기 개발 환경 구성과 기존 DB 환경 변수 충돌 해결

- 기록일: 2026-10-29 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 초기 환경 해결, 서비스 기능 구현은 후속
- 유형: 개발 환경 / 문제 해결 / 문서화
- 담당·검토: 사용자 요청에 따라 Codex가 구성·검증을 지원. 팀원 개인별 구현·검토 기여는 별도 확인
- 관련 이슈·PR·커밋: [초기 커밋 54dcd72](https://github.com/Cho-Mi-Kim/jibro/commit/54dcd72a800fe3cb23c020a69be2ff9b9a4e11e1)

## 배경·문제

팀은 React·TypeScript와 Spring Boot·Java 17·PostgreSQL을 사용하고, 하나의 저장소에서 프론트·백엔드의 실행·빌드·배포를 각각 관리하기로 했다. 기존 `jibro-housing-prep`을 보존하면서 새 `jibro-housing` 프로젝트가 필요했다.

초기 실행 과정에서 다음 문제가 확인됐다.

- PC의 기본 Node.js는 22 계열이었고 프로젝트의 선택 기준은 24 LTS였다.
- Docker Desktop의 초기 실행 오류로 DB 연결 검증을 바로 진행하지 못했다. 사용자가 Docker를 직접 실행한 뒤 연결이 가능해졌다.
- DB 통합 테스트는 통과했지만 실제 백엔드 실행에서는 MariaDB 드라이버 로딩 실패가 발생했다. 기존 프로젝트의 일반 `DB_URL` 환경 변수가 새 프로세스에 상속되는 것이 원인이었다.

## 고려한 선택지

아래 비교는 작업에서 선택한 방식을 설명하기 위한 정리다. 모든 대안에 별도 실험·성능 측정을 했다는 뜻은 아니다.

| 선택지 | 고려 사항 |
| --- | --- |
| PC 전역 Node.js·DB 환경 변수 변경 | 기존 프로젝트에 영향을 줄 수 있음 |
| 새 프로젝트의 런타임·DB 설정을 별도로 적용 | 실행 절차를 명확히 기록해야 하지만 기존 환경의 변경을 줄일 수 있음 |
| 개발 DB를 통합 테스트에 함께 사용 | 테스트 데이터가 개발 상태에 영향을 줄 수 있음 |
| 개발 DB와 테스트 DB 분리 | 별도 초기화가 필요하지만 검증 범위를 구분할 수 있음 |

## 결정·이유

프로젝트 전용 Node.js 24 런타임과 실행 도우미를 준비하고 전역 PATH는 영구 변경하지 않았다. Java Toolchain은 17로 설정했으며, 팀 기준 배포판 Temurin 17과 현재 PC에서 검증한 OpenJDK 17을 구분해 기록했다.

Docker Compose의 새 프로젝트·볼륨과 로컬 포트 `15432`를 사용하고 개발 DB `jibro`, 테스트 DB `jibro_test`를 분리했다. 로컬 실행 도우미는 이 프로젝트의 `.env` 또는 명시한 PostgreSQL 기본 주소를 사용하도록 해 다른 프로젝트의 일반 `DB_URL`에 의존하지 않도록 했다.

## 변경·해결 과정

1. 프론트·백엔드 공식 템플릿을 생성하고 실제 선택 버전을 고정했다.
2. 템플릿의 기본 린터를 팀 기준 ESLint로 맞추고 Prettier·타입 검사·테스트를 구성했다.
3. PostgreSQL 연결 확인 API와 Vite의 `/api` proxy를 연결했다.
4. Flyway·JPA validate와 테스트용 엔티티를 이용해 Querydsl Q 클래스 생성·AND/OR 조회를 확인했다. 테스트 테이블은 운영 모델로 확정하지 않았다.
5. 실제 서버 실행 실패 로그와 환경 변수 이름·URL 종류를 대조해 상속된 DB 주소의 충돌을 확인했다. 비밀번호·실제 접속 문자열은 기록에 넣지 않았다.
6. 실행 도우미의 주소 선택을 수정한 뒤 직접 API·프론트 proxy·DB 연결을 다시 확인했다.
7. 최신 기준 문서 5개와 참조 목업 10장을 프로젝트 `docs/`로 복사하고 링크를 정리했다.

## 검증·결과

당시 실행 결과는 [검증 기록](../../verification.md)에 남겼다.

- 프론트 `npm run ci`: 포맷·린트·타입·테스트 2건·빌드 통과.
- 백엔드 `gradlew build`: 단위 테스트 2건·실행 JAR 생성 통과.
- `gradlew integrationTest`: 별도 PostgreSQL에서 Flyway·JPA validate·Querydsl AND/OR 조회 1건 통과.
- `spotlessApply`·`spotlessCheck`: 설치된 IntelliJ CLI와 공유 XML로 통과. 검사에 약 4분이 걸려 CI 실행 환경·시간 검토는 후속으로 남겼다.
- 직접 API와 프론트 proxy의 응답 모두 `status: UP`, `database: UP`. 프론트 HTML 200, 비공개 경로 403 확인.
- 문서 원본 보존·복사 이미지 동일성·상대 링크 확인. 초기 커밋을 `main`·`develop`에 반영한 것을 확인.

## 기여·한계

이 기록은 대화에서 관찰한 실행 과정과 저장소 증거를 정리한 것이다. Git 커밋 작성자만으로 개인별 구현 기여를 확정하지 않는다. 초기 환경 검증이 실제 공고 검색·로그인·준비 관리·AWS 운영 검증을 뜻하지 않는다. 사용자 효과·성능 향상률을 측정하지 않았으며 팀원별 환경 적용·원격 CI 실행은 별도 확인이 필요하다.

## 발표·포트폴리오 메모

“기존 프로젝트를 보존하면서 새 모노레포의 개발 환경을 구성했다. 기존 DB 환경 변수가 새 서버 실행에 영향을 주는 문제를 확인하고 프로젝트별 설정을 분리했다. 별도 테스트 DB에서 조회·마이그레이션을 확인하고 화면에서 API와 DB까지 연결되는 흐름을 검증했다.”

개인 포트폴리오에 사용할 때는 본인이 직접 수행·검토한 범위와 AI 보조·팀 협업 범위를 확인해 문장을 조정한다.

## 후속 작업

- 팀원별 환경 적용·원격 Actions·보호 브랜치와 Java 포맷 CI 연결 확인.
- 실제 공고 모델·마이그레이션·API, 로그인·JWT·소셜 인증, 준비 관리·검수 기능 구현.
- 제품 흐름의 사용자 검증과 실제 배포·복구 절차 확정.

## 변경 파일

- `scripts/Use-DevEnvironment.ps1`, `scripts/Initialize-Local.ps1`
- `frontend/vite.config.ts`, `frontend/src/features/health/api/healthApi.ts`
- `backend/src/main/java/org/jibro/global/common/HealthController.java`
- `backend/src/test/java/org/jibro/support/DatabaseIntegrationTest.java`
- `docs/verification.md`, `docs/versions.md`, `docs/README.md`
