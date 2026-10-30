# 개인 VS Code 설정의 Git 추적 제외

- 기록일: 2026-10-31 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 제출 준비
- 유형: 개발 환경 설정
- 담당·검토: 사용자 gitignore 변경, Codex 범위 검증·커밋·PR 준비; 팀 검토자 미확인

## 배경·문제

사용자가 .gitignore에 추가한 .vscode/ 항목을 화면 이미지와 분리해 제출하도록 요청했다.

## 고려한 선택지

IDE 설정 파일 자체를 올리는 대신 기존 사용자 변경인 제외 규칙 한 줄만 적용한다. 공유할 VS Code 설정을 별도로 정하는 작업은 이번 범위에 포함하지 않는다.

## 결정·이유

최신 develop의 c78d0fa에서 chore/ignore-vscode-20261031 브랜치를 만들고 .gitignore에 .vscode/를 추가했다. 경로 내부의 파일을 삭제하거나 수정하지 않는다.

## 변경·해결 과정

기존 사용자 .gitignore 변경본을 독립 작업 사본에 복사했다. 실제 IDE 파일이나 비밀값·이미지는 커밋에 포함하지 않는다.

## 검증·결과

- git diff로 .vscode/ 규칙 한 줄만 추가됨을 확인.
- git check-ignore로 루트 .vscode 및 frontend 하위의 새로운 미추적 설정 경로가 제외되는지 확인.
- git ls-files로 frontend/.vscode/extensions.json 및 settings.json 2개가 이미 추적 중임을 확인. 두 파일은 그대로 유지한다.
- diff 공백 검사 성공. 런타임 코드 변경이 없어 제품 테스트는 반복하지 않는다.

## 기여·한계

무시 규칙은 향후 미추적 파일 추가를 방지하며 이미 추적 중인 파일을 자동 삭제하는 기능은 아니다. 이번 저장소의 frontend/.vscode/extensions.json과 settings.json은 기존 추적 파일이므로 계속 공유된다. 새 미추적 설정 파일은 하위 폴더에서도 무시되므로 추가 공유 시 예외 규칙이나 명시적 추가 여부를 검토해야 한다.

## 발표·포트폴리오 메모

개인 개발 환경 파일과 공유 코드의 범위를 분리했다. 실행 성능·사용자 효과 개선으로 확대하지 않는다.

## 후속 작업

향후 팀 공통 VS Code 설정을 공유하기로 합의하면 예외 규칙을 검토한다. 병합은 별도 요청 범위다.

## 변경 파일

- .gitignore
- docs/history/entries/2026-10-31-ignore-vscode.md
- docs/history/README.md
