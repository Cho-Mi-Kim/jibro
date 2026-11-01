# 백엔드

Spring Boot 4.1.2 · JDK 17 · Gradle Wrapper 8.14.6 · PostgreSQL 17 · JPA · Flyway · OpenFeign Querydsl 7.7.

실행 절차는 이 안내와 [개발 시작 안내](../docs/getting-started.md), 패키지·API·인증·DB 계약은 [코딩 컨벤션](../docs/conventions/coding-conventions.md), 제품 정책은 [기획안](../docs/planning/project-proposal.md)을 기준으로 확인합니다. [문서 전체 안내](../docs/README.md).

1. IntelliJ IDEA에서 이 폴더를 Gradle 프로젝트로 엽니다.
2. Project SDK·Gradle JVM 17, Wrapper 사용, Build/run/test는 Gradle로 맞춥니다.
3. `config/intellij-java-style.xml`을 코드 스타일로 가져옵니다.
4. PostgreSQL을 준비하고 `DB_PASSWORD` 등 [환경 변수](../docs/getting-started.md)를 설정합니다.

이 폴더에서 직접 실행하는 명령:

```powershell
.\gradlew.bat bootRun
.\gradlew.bat clean build
.\gradlew.bat integrationTest
```

루트 `.env`는 Spring이 자동으로 읽지 않습니다. 루트의 `scripts/Start-Backend.ps1`은 환경 변수를 읽고 이 폴더에서 bootRun을 실행합니다. 운영 JAR은 `build/libs/`에 생성됩니다.

`build`는 DB가 필요 없는 단위 테스트를 실행합니다. `integrationTest`는 `jibro_test` DB에서만 Flyway·JPA·Q 클래스·복합 조회를 검증합니다. Q 클래스·build 폴더는 커밋하지 않습니다.

계정·JWT/갱신 토큰·소셜 연결, 공고 복합 검색, 개인 준비 저장·계산, 관리자 검수·원고 편집, LH 수집 API를 구현했습니다. 실제 동작과 남은 외부 설정은 [백엔드 계약](../docs/architecture/backend-contracts.md)을 확인합니다. JWT_SECRET과 DB 비밀값은 커밋하지 않습니다.

Java 포맷은 `IDEA_FORMATTER_PATH`와 실행 중이지 않은 IntelliJ CLI가 필요합니다. `spotlessCheck`·`spotlessApply`를 명시적으로 실행하며, 같은 포맷 환경이 확인되기 전까지 `build`·CI 필수 검사에는 연결하지 않았습니다.

가상 공고로 실제 저장을 확인하려면 루트에서 scripts/Start-Backend.ps1 -SampleData를 실행합니다. 운영에서는 샘플 프로필을 켜지 않으며 별도 DB를 사용합니다.
