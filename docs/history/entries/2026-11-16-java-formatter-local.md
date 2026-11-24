# Windows 개발 환경의 Java 서식 검사

- 기록일: 2026-11-16 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결
- 유형: 문제 해결

서류 상태 복원 작업에서 `spotlessCheck`가 프로젝트 기본 경로의 IntelliJ 실행 파일을 찾지 못했다. 이 컴퓨터에서 이전에 사용한 IntelliJ IDEA 2026.2.3 설치를 확인하고, 개발 저장소의 `.tools/idea`를 해당 설치 디렉터리에 연결했다. 기본 경로와 `IDEA_FORMATTER_PATH`를 지원하는 기존 Gradle 설정을 그대로 사용한다. 이 연결은 로컬 개발 도구 디렉터리에 있다.

수정 중이던 소스를 먼저 보관한 뒤 `backend/config/intellij-java-style.xml`로 Java 파일을 정리했다. 작업 전 사본 기준으로 347개 파일의 서식을 정리했고 6개 파일은 줄바꿈만 LF로 바뀌었다. 최종 파일 389개를 별도 디렉터리에서 동일한 IntelliJ와 XML로 다시 포맷한 결과 바이트 차이가 없었다.

최종 소스의 백엔드 단위 테스트 672개와 PostgreSQL 통합 테스트 361개가 통과했다. AI 보조로 로컬 환경을 복구하고 검증했으며, 결과의 범위는 이 Windows 개발 환경이다.

최종 파일을 고정하고 `gradlew.bat --offline spotlessCheck`를 실행했다. IntelliJ 실행 389회, 서식 차이 0건, 린트 0건으로 성공했다.
