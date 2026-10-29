# 초기 설정 검증 기록

작성일: 2026-10-29. 이 문서는 실제 실행 결과를 기준으로 갱신합니다.

## 구성 완료

- 독립된 새 `jibro-housing` 폴더·프론트·백엔드 프로젝트
- Node.js 24 프로젝트 전용 런타임, JDK 17 Toolchain, Gradle Wrapper 8.14.6
- VS Code 설정·IntelliJ 공유 Java 스타일 XML
- 로컬 PostgreSQL Compose, 환경 변수 예시와 임의 비밀번호 초기화 도우미
- Vite `/api` proxy·개발 확인 화면·DB 확인 API
- OpenFeign Querydsl 7.7 annotation processing·테스트용 복합 조회
- 프론트·백엔드 개별 CI 구성과 PR 템플릿

## 실행 결과

- `npm run ci`: 성공. Prettier·ESLint 10·TypeScript·Vitest/React Testing Library 2건·Vite 빌드 통과.
- `gradlew build`: 성공. DB 의존 없는 단위 테스트 2건과 실행 JAR 생성 확인.
- `compileTestJava`: 성공. 테스트 소스의 Q 클래스 생성 및 Querydsl API 컴파일 확인.
- `gradlew integrationTest`: 성공. 별도 PostgreSQL 17.6 DB `jibro_test`에서 Flyway·JPA validate·Q 클래스·AND/OR 복합 조회 1건 통과.
- `gradlew spotlessApply`·`gradlew spotlessCheck`: 모두 성공. 설치된 IntelliJ IDEA 2025.1.4.1의 CLI와 공유 XML로 Java 소스 포맷 적용·검사 확인.
- Docker Compose: 새 `jibro-housing-dev-postgres-1`이 healthy. `jibro`·`jibro_test` 분리 확인.
- API 직접 호출과 Vite `/api` proxy 호출: 모두 `status: UP`, `database: UP`.
- 프론트 HTML 200, 초기 비공개 API 경로 403 확인.
- `.env`·`.tools/` Git 제외, VS Code 공통 설정·Gradle Wrapper JAR 포함 가능 여부 확인.

검증 환경: 프로젝트 전용 Node.js 24.22.2, 현재 PC의 OpenJDK 17, Gradle Wrapper 8.14.6. 팀의 Java 배포판 기준은 Temurin 17이며 CI도 Temurin으로 지정했습니다. 이 PC의 전역 JDK·Node.js 설정은 변경하지 않았습니다.

로컬 실행 도우미는 다른 프로젝트의 일반 `DB_URL`을 상속하지 않고 이 프로젝트의 `.env` 또는 전용 기본 주소를 사용합니다. 시스템 환경 변수나 기존 프로젝트 설정은 변경하지 않습니다.

## 후속 설정

- Java 포맷: 로컬 CLI 적용·검사는 확인. 검사에 약 4분이 걸렸으므로 팀 IDE·CLI·CI 동일 버전·결과·실행 시간을 확인한 뒤 필수 검사 연결. 런타임 없는 실행을 검사 성공으로 처리하지 않음.
- 인증: JWT·Refresh Token·OAuth2 실제 구현, 쿠키·CSRF·권한 검증.
- 도메인: 공고·개인 준비·관리자 모델과 API 계약 및 운영 Flyway SQL.
- GitHub: 최초 기준 커밋은 `Cho-Mi-Kim/jibro`의 `main`과 `develop` 시작점으로 사용합니다. 이후 PR 검사 실제 실행·보호 브랜치 설정은 후속 작업입니다.
- AWS 배포와 복구 절차·비용 설정: 후속 설계.

## 문서 편입 검증 — 2026-10-29

- 최신 팀 컨벤션·코딩 컨벤션·기획안·기획 세부안·화면설계서 5개를 날짜 없는 파일명으로 `docs/`에 편입했습니다. 원본 파일 5개의 해시가 변경되지 않았음을 확인했습니다.
- 기존 화면설계서가 참조하는 통합 목업 10장을 `docs/assets/mockups/`에 복사하고 원본과 같은 내용임을 확인했습니다.
- 문서 안내·현재 프로젝트 버전·초기 구현 상태를 연결하고, 관련 Markdown 14개의 상대 링크 107개와 문서 내부 앵커 7개를 확인했습니다.
- 이번 편입은 Markdown과 문서 이미지 변경입니다. 런타임 코드·환경 변수·DB·배포 설정은 변경하지 않았으며 기존 빌드·테스트를 다시 실행하지 않았습니다.

## 작업 기록 자동화 검증 — 2026-10-29

- `node --test scripts/project-history.test.mjs`: 7건 통과. 한국 날짜 경계, 실제 Git hook, 중복 방지, stage 범위·미선택 수정 보존, 기존 hook 설정 보존을 임시 저장소에서 확인했습니다.
- Codex 작업 지침·주제별 기록 양식·자동 목록·Git의 변경 사실 수집을 구성했습니다. 초기 설정·DB 충돌 해결과 이번 기록 자동화의 실제 근거를 두 기록으로 정리했습니다.
- 현재 PC에는 이 저장소 전용 hook을 설치했습니다. 실제 `git hook run pre-commit`에서 stage된 변경이 없으면 기록을 생성하지 않는 것도 확인했습니다. clone에는 로컬 Git 설정이 복사되지 않으므로 다른 팀원은 한 번 설치해야 합니다. hook 미실행·실패 시 경고와 수동 보완이 필요합니다.
- PR에는 기록 도구 테스트 job을 추가했습니다. 원격 Actions는 [작업 기록 자동화 PR #1의 Checks](https://github.com/Cho-Mi-Kim/jibro/pull/1/checks)에서 커밋별 결과를 확인합니다. 위 로컬 검증 단계에서는 제품 런타임·DB 로직 변경이 없어 프론트·백엔드 검사를 추가로 반복하지 않았으며 원격 PR에서는 세 CI 작업을 실행합니다.
