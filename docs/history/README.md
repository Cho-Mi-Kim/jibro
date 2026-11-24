# 프로젝트 진행·의사결정·문제 해결 기록

최종 수정일: 2026-10-30

팀원이 무엇이 왜 바뀌었는지 이해하고, 발표·회고·포트폴리오에서 문제 해결의 근거를 재사용하기 위한 기록입니다. [현재 제품 기획](../planning/project-proposal.md)·[기술 규칙](../conventions/coding-conventions.md)은 현재 기준을, 이 폴더는 그 기준에 이른 과정과 변경 이력을 설명합니다.

## 자동 기록 방식

| 경로 | 자동으로 남기는 내용 | 사람이 확인할 내용 |
| --- | --- | --- |
| Codex 작업 | 루트 `AGENTS.md`에 따라 작업을 마칠 때 실제 문제·대안·결정·검증·한계를 기록 | 담당자·검토자, 판단 근거의 정확성, 공개 가능한 표현 |
| Git 커밋 | 작업 기록이 함께 stage되지 않았으면 변경 파일·증감·기준 커밋을 자동 수집하고 목록 갱신 | 자동 수집 기록의 ‘보완 필요’ 항목. Git만으로 고민·원인·테스트 성공을 추정하지 않음 |

공식 근거: [Codex 프로젝트 지침](https://learn.chatgpt.com/docs/agent-configuration/agents-md), [Git hook 안내](https://git-scm.com/docs/githooks).

Codex의 기록 갱신은 프로젝트 지침을 따르는 작업 과정의 일부입니다. 파일 편집 전체를 상시 감시하거나 다른 대화·회의를 자동으로 읽는 기능은 아닙니다. 새 Codex 작업은 `jibro-housing/` 또는 그 하위 프로젝트에서 시작하고, 이미 열린 대화에서는 새 지침을 확인하도록 요청합니다.

## 팀원별 최초 설치

Git hook 설정은 clone으로 공유되지 않으므로 각자 이 저장소 루트에서 **한 번** 실행합니다. Git과 Node.js가 필요하며 프로젝트의 Node.js 24 환경을 권장합니다.

```powershell
node scripts/project-history.mjs install
```

설치 명령은 이 저장소의 로컬 `core.hooksPath`만 `.githooks`로 지정합니다. 기존 hook 경로가 설정되어 있으면 덮어쓰지 않고 안내합니다. hook은 저장소 밖에 문서를 작성하지 않으며 커밋·push를 직접 실행하지 않습니다.

기록 도구를 변경했을 때는 `node --test scripts/project-history.test.mjs`로 임시 Git 저장소에서 동작을 확인합니다. 같은 검사를 PR의 `project-history` CI job에도 구성했습니다.

이 PC에서 프로젝트 전용 Node.js를 쓰려면 먼저 `. .\scripts\Use-DevEnvironment.ps1`을 실행합니다. 설치 후 일반 `git commit`을 할 때 자동 기록이 동작합니다. `--no-verify`·일부 GUI/서버 작업처럼 hook이 실행되지 않는 경로는 수동 기록 또는 Codex 기록이 필요합니다.

## 기록 작성·갱신

Codex 작업은 지침에 따라 자동으로 기록합니다. 직접 작업한 경우 초안을 생성할 수 있습니다.

```powershell
node scripts/project-history.mjs new --slug notice-search --title "공고 복합 검색 조건 처리"
node scripts/project-history.mjs sync
```

초안은 [양식](_template.md)에 맞춰 배경·대안·선택 이유·조치·검증·역할·한계·재사용 문장을 채웁니다. 같은 작업은 해당 기록을 이어서 수정하고 새 주제는 별도 파일을 만듭니다. 상세 기록을 코드와 함께 stage하면 hook은 별도 자동 초안을 만들지 않고 목록만 갱신합니다.

hook이 추가할 수 있는 파일은 새 자동 수집 기록과 이 문서의 자동 목록뿐입니다. **코드나 사용자가 stage하지 않은 기존 변경을 자동으로 올리지 않습니다.** 이 문서에 stage하지 않은 수정이 있어 안전하게 목록을 갱신할 수 없으면 경고하고 기록을 생략하며 커밋을 막지 않습니다. Node.js 미설치·스크립트 오류도 경고하므로 그 경우 기록을 수동 보완합니다. 비밀값이나 전체 diff·로그 본문은 수집하지 않습니다.

## 발표·포트폴리오에 사용할 때

1. 기록에서 실제 사용자의 문제 또는 기술적 제약을 한 문장으로 고릅니다.
2. 실제 검토한 대안과 선택 이유를 설명합니다. 사후 비교를 덧붙이면 당시 의사결정과 구분합니다.
3. 변경한 구조·코드·협업 방식과 검증 결과를 연결합니다.
4. 본인이 맡은 범위·팀원 협업·AI 보조·남은 제약을 구분합니다.
5. 확인하지 않은 사용자 효과·운영 성능·개인 성과를 추가하지 않습니다.

업무 기록 자체가 외부 발표용 완성 원고는 아닙니다. ‘맥락 보완 필요’·‘작성 중’ 항목은 보완 전까지 성과 근거로 사용하지 않습니다. Git hook의 날짜는 수집일이며 실제 작업 시작일·실제 커밋 작성자·배포일을 뜻하지 않습니다.

## 기록 목록

아래 목록은 `sync` 또는 커밋 hook이 갱신합니다. 목록 안을 직접 편집하지 않습니다.

<!-- PROJECT-HISTORY:START -->
| 기록일 | 내용 | 기록 상태 |
| --- | --- | --- |
| 확인 필요 | [알림 화면의 설정 팝업](entries/2026-11-05-alert-settings-dialog.md) | 확인 필요 |
| 확인 필요 | [마이 프로필과 준비 건수](entries/2026-11-05-my-profile-summary.md) | 확인 필요 |
| 확인 필요 | [공고 카드 펼침과 상단 이동 (2026-11-05)](entries/2026-11-05-notice-card-expand.md) | 확인 필요 |
| 확인 필요 | [실제 코드 기반 개념 모델 HTML](entries/2026-11-06-code-concept-model.md) | 확인 필요 |
| 확인 필요 | [자동 검수·공개와 Gemini 연결](entries/2026-11-07-automatic-review.md) | 확인 필요 |
| 확인 필요 | [LH 실제 수집 확인 — 2026-11-07](entries/2026-11-07-lh-live-key-verification.md) | 확인 필요 |
| 확인 필요 | [신규 수집 공고의 부분 준비 안내와 기본 정렬](entries/2026-11-08-automatic-partial-and-sorting.md) | 확인 필요 |
| 확인 필요 | [충돌 항목만 확인 필요로 분리한 준비 안내](entries/2026-11-08-partial-guidance.md) | 확인 필요 |
| 확인 필요 | [개별 결과 발표 설명의 미래 자동 수집 반영](entries/2026-11-10-automatic-individual-announcements.md) | 확인 필요 |
| 확인 필요 | [현재 수집 공고의 개별 발표 누락 점검](entries/2026-11-10-current-individual-announcement-audit.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 공고 서류마감 누락 보완](entries/2026-11-10-document-deadlines.md) | 확인 필요 |
| 확인 필요 | [요약의 긴 행정 표현을 짧은 말로 표시](entries/2026-11-11-summary-plain-language.md) | 확인 필요 |
| 확인 필요 | [대구경북 든든전세 분양전환형 검수 항목 재분류](entries/2026-11-12-daegu-conversion-scope.md) | 확인 필요 |
| 확인 필요 | [공고 필터 버튼 고정](entries/2026-11-12-filter-sheet-scroll.md) | 확인 필요 |
| 확인 필요 | [준비 안내 원형 상태 아이콘](entries/2026-11-12-guide-state-icons.md) | 확인 필요 |
| 확인 필요 | [5173 재시작 실행 파일](entries/2026-11-12-local-restart-launcher.md) | 확인 필요 |
| 확인 필요 | [공고 선택 자동 스크롤 속도 조정](entries/2026-11-12-notice-scroll-speed.md) | 확인 필요 |
| 확인 필요 | [공식 PDF 근거 페이지 열기](entries/2026-11-12-official-pdf-page.md) | 확인 필요 |
| 확인 필요 | [내 준비 세부탭 공고 제목](entries/2026-11-12-preparation-context-title.md) | 확인 필요 |
| 확인 필요 | [과거 공고 종료 처리와 비주거 모집 서비스 제외](entries/2026-11-12-service-disposition.md) | 확인 필요 |
| 확인 필요 | [미확인 조건 이어서 답변](entries/2026-11-12-unanswered-conditions.md) | 확인 필요 |
| 확인 필요 | [자동 원문 위치 연결 보완 (진행 중)](entries/2026-11-13-automatic-evidence-location.md) | 확인 필요 |
| 확인 필요 | [서류 카드와 공고문 표시 오류](entries/2026-11-13-document-viewer-fix.md) | 확인 필요 |
| 확인 필요 | [비로그인 화면 로딩 복구](entries/2026-11-13-guest-loading-recovery.md) | 확인 필요 |
| 확인 필요 | [홈 공고 제목 요약과 펼침](entries/2026-11-13-home-title-disclosure.md) | 확인 필요 |
| 확인 필요 | [공고 상세 핵심 요약 스크롤](entries/2026-11-13-notice-summary-scroll.md) | 확인 필요 |
| 확인 필요 | [거창 신원 다자녀 공고 서류 발급 안내](entries/2026-11-13-official-document-issuance.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 공고 첨부 재점검](entries/2026-11-14-active-attachment-audit.md) | 확인 필요 |
| 확인 필요 | [기존·신규 공고의 서류 발급처 자동 연결](entries/2026-11-14-official-document-issuance.md) | 확인 필요 |
| 확인 필요 | [접수기간 검수 저장 계층 수정과 전체 테스트 검증](entries/2026-11-15-application-period-review-boundary.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 LH 공고의 공식 첨부 자동 연결](entries/2026-11-15-automatic-notice-files.md) | 확인 필요 |
| 확인 필요 | [공고 목록 하단에 현재·전체 페이지 표시](entries/2026-11-15-notice-pagination.md) | 확인 필요 |
| 확인 필요 | [지역 필터의 ‘외’ 중복 선택지 통합](entries/2026-11-15-notice-region-filters.md) | 확인 필요 |
| 확인 필요 | [LH 공식 공급 지역을 공고 검색에 연결](entries/2026-11-15-official-supply-regions.md) | 확인 필요 |
| 확인 필요 | [공식 첨부 15개와 제출 양식 17개 반영](entries/2026-11-15-reviewed-notice-files.md) | 확인 필요 |
| 확인 필요 | [상단 알림 아이콘의 읽지 않은 알림 표시](entries/2026-11-15-unread-alert-indicator.md) | 확인 필요 |
| 확인 필요 | [공고 상세에서 검색 목록 위치 복원](entries/2026-11-16-notice-list-detail-return.md) | 확인 필요 |
| 확인 필요 | [공개 공고의 누락된 접수 시각 대조](entries/2026-11-16-reviewed-intake-times.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 및 일정 확인 필요 공고 7건의 접수 시간 재검수](entries/2026-11-16-seven-intake-windows.md) | 확인 필요 |
| 확인 필요 | [서류별 제출 대상과 발급 방법 공통 적용](entries/2026-11-16-shared-document-summaries.md) | 확인 필요 |
| 확인 필요 | [공고 모집 지역과 주택 유형의 공통 표시](entries/2026-11-16-shared-housing-facts.md) | 확인 필요 |
| 확인 필요 | [공고 운영기관의 이름·로고 표시 통합](entries/2026-11-16-shared-notice-agency.md) | 확인 필요 |
| 확인 필요 | [접수 상태와 준비 안내 상태의 공통 표시](entries/2026-11-16-shared-notice-statuses.md) | 확인 필요 |
| 확인 필요 | [공고 제목 축약 규칙 공유](entries/2026-11-16-shared-notice-titles.md) | 확인 필요 |
| 확인 필요 | [일정 요약의 공통 표시](entries/2026-11-16-shared-schedule-summaries.md) | 확인 필요 |
| 확인 필요 | [접수 예정 공고 43건의 자격·서류 대조](entries/2026-11-17-active-field-coverage.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 공고 전체 자격·서류 재검수](entries/2026-11-17-all-active-preparation-review.md) | 확인 필요 |
| 확인 필요 | [첨부를 읽을 수 없는 활성 모집의 대체 원문 검수](entries/2026-11-17-alternative-official-originals.md) | 확인 필요 |
| 확인 필요 | [신규 공고의 방문 접수 일정 자동 반영](entries/2026-11-17-automatic-visit-hours.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 전체의 기관 공지와 서식 재검수](entries/2026-11-17-current-agency-preparation.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 공고의 대체 원문과 공식 서식 재검수](entries/2026-11-17-current-alternative-originals.md) | 확인 필요 |
| 확인 필요 | [현재 공고의 날짜별 방문 접수 일정 재검수](entries/2026-11-17-current-daily-visit-sessions.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 공고의 방문 접수 시간 재대조](entries/2026-11-17-current-visit-hours.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 공고의 기관 후속 공지와 첨부 서식 자동 대조](entries/2026-11-17-future-agency-preparation.md) | 확인 필요 |
| 확인 필요 | [신규 LH 공고의 세부 자격·서류 항목 대조](entries/2026-11-17-future-guidance-items.md) | 확인 필요 |
| 확인 필요 | [수집 공고의 자격·서류 판독과 정정본 항목 대조](entries/2026-11-17-future-item-coverage.md) | 확인 필요 |
| 확인 필요 | [방문 접수 시간의 공식 자료 대조](entries/2026-11-17-future-visit-hour-agreement.md) | 확인 필요 |
| 확인 필요 | [접수처별 점심시간과 마지막 날 방문 마감](entries/2026-11-17-office-specific-intake.md) | 확인 필요 |
| 확인 필요 | [접수 중·예정 7개 공고의 자격·서류 후속 대조](entries/2026-11-17-seven-agency-forms.md) | 확인 필요 |
| 확인 필요 | [정읍상동2·3, 공주, 광주 북구 공식 원문 재대조](entries/2026-11-17-three-official-routes.md) | 확인 필요 |
| 확인 필요 | [신청 완료 여부 공통 표시](entries/2026-11-18-application-completion-badge.md) | 확인 필요 |
| 확인 필요 | [조건 답변 상태 공통 표시](entries/2026-11-18-condition-answer-status.md) | 확인 필요 |
| 확인 필요 | [서류 발급처와 공식 양식 링크 공통 표시](entries/2026-11-18-document-issuance-links.md) | 확인 필요 |
| 확인 필요 | [서류 준비 상태 공통 표시](entries/2026-11-18-document-state-badge.md) | 확인 필요 |
| 확인 필요 | [일정 완료 상태 공통 표시](entries/2026-11-18-schedule-completion-badge.md) | 확인 필요 |
| 확인 필요 | [공식 근거 링크 공통 표시](entries/2026-11-18-shared-evidence-links.md) | 확인 필요 |
| 확인 필요 | [준비 진행률 공통 표시](entries/2026-11-18-shared-preparation-progress.md) | 확인 필요 |
| 확인 필요 | [공식 확인이 필요한 항목의 공통 표시](entries/2026-11-18-shared-review-issues.md) | 확인 필요 |
| 확인 필요 | [AWS 무료 플랜 운영 배포](entries/2026-11-22-aws-free-deployment.md) | 확인 필요 |
| 확인 필요 | [무료 메일 발송 연결 준비](entries/2026-11-22-free-mail-connection.md) | 확인 필요 |
| 확인 필요 | [문의·개인정보 페이지 로고와 공통 하단 정리](entries/2026-11-24-public-page-branding.md) | 확인 필요 |
| 확인 필요 | [사이트 기본 운영 항목 점검](entries/2026-11-24-site-basics-audit.md) | 확인 필요 |
| 확인 필요 | [첫 접속 Cloudflare 사람 확인](entries/2026-11-24-visitor-verification.md) | 확인 필요 |
| 2026-11-24 | [Iron Laws 실제 실행과 상위 지적 대조](entries/2026-11-24-iron-laws-audit.md) | 검토 완료 |
| 2026-11-24 | [리팩토링 지적 14건의 책임 통합과 실패 처리 보완](entries/2026-11-24-refactoring-findings-fixed.md) | 완료 |
| 2026-11-24 | [리팩토링 수정 운영 배포](entries/2026-11-24-refactoring-production-deploy.md) | 완료 |
| 2026-11-24 | [첨부 원칙에 따른 리팩토링 점검](entries/2026-11-24-refactoring-review.md) | 검토 완료 |
| 2026-11-24 | [공개 PDF 자원 경계와 비밀번호 변경 재인증 보완](entries/2026-11-24-security-findings-fix.md) | 검증 완료 |
| 2026-11-24 | [공개 운영 안내·방문 통계·문의 메일 구현](entries/2026-11-24-site-basics-implementation.md) | 근거 정리·담당자 검토 전 |
| 2026-11-24 | [시각화 제외 로컬 개선 사항 팀 검토 업로드](entries/2026-11-24-team-review-upload.md) | 근거 정리·팀 검토 전 |
| 2026-11-23 | [신청 후 결과·서류·계약·입주 기록 연결](entries/2026-11-23-application-followup.md) | 근거 정리·담당자 검토 전 |
| 2026-11-23 | [북구 PDF의 떨어진 표와 자동 맞춤 질문 연결](entries/2026-11-23-detached-pdf-tables.md) | 검증 완료·팀 담당자 검토 전 |
| 2026-11-23 | [PC 내 준비 서류 상세 상단 이동](entries/2026-11-23-document-detail-scroll.md) | 확인 필요 |
| 2026-11-23 | [보류 수치 정정과 공식 원문 대조·상황별 질문 복구](entries/2026-11-23-held-guidance-personalization.md) | 근거 정리·담당자 검토 전 |
| 2026-11-23 | [PDF·한글본 대조와 신청 유형별 자동 질문 보완](entries/2026-11-23-paired-applicant-automation.md) | 근거 정리·담당자 검토 전 |
| 2026-11-23 | [서울 영구임대·수원당수1·2 원문 대조와 맞춤 준비 안내 공개](entries/2026-11-23-seoul-suwon-personal-guidance.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [제공 중인 공고 전체의 상황 선택·질문·서류 연결](entries/2026-11-22-all-applicant-situations.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [신청자 상황 질문과 맞춤 준비 안내 분리](entries/2026-11-22-applicant-guidance.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [공식 모집 유형에 따른 질문과 서류 분기](entries/2026-11-22-applicant-official-types.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [선택한 신청 유형에 따른 입력 질문 분기](entries/2026-11-22-applicant-question-branches.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [새 공고의 상황별 질문 생성과 기존 유지 12건 재확인](entries/2026-11-22-automatic-applicant-situations.md) | 검토 완료 |
| 2026-11-22 | [첨부 구조 대조와 유지 12건의 맞춤 질문 보완](entries/2026-11-22-automatic-source-dialogue.md) | 검토 완료 |
| 2026-11-22 | [파주운정 A4BL의 신청 상황별 질문](entries/2026-11-22-paju-applicant-questions.md) | 검토 완료 |
| 2026-11-22 | [답변에 맞춘 내 준비 조건·서류·일정 표시](entries/2026-11-22-preparation-personal-scope.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [자격 요약의 책임 통합과 비교 직접 진입 보완](entries/2026-11-22-qualification-summary-refactor.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [화면별 코드 지연 로딩과 큰 번들 경고 해소](entries/2026-11-22-route-code-splitting.md) | 근거 정리·담당자 검토 전 |
| 2026-11-22 | [그림·자동 번호·여러 쪽 표의 원문 대조와 상황별 질문 연결](entries/2026-11-22-source-structure-applicant-automation.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [신청 유형·가족관계 답변 모순 확인과 필수 서류 누락 방지](entries/2026-11-21-answer-consistency-family-documents.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [공식 안내 변경에 따른 가이드 자동 재확인](entries/2026-11-21-guide-automatic-refresh.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [가이드 전체 내용을 공식 자료로 재검토](entries/2026-11-21-guide-content-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [가이드와 공고 목록의 페이지 이동 표시 통일](entries/2026-11-21-guide-pagination-ui.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [가이드 줄바꿈과 공고 목록 페이지 스크롤](entries/2026-11-21-guide-readability-notice-page-scroll.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [현재 제공 공고 전체의 조건 질문·답변 개별 검토](entries/2026-11-21-listed-condition-improvement.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [포항블루밸리 조건 카드·질문 표현 개별 검토](entries/2026-11-21-pohang-condition-presentation-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-21 | [Google·카카오 로컬 로그인 연결과 재시도 복귀](entries/2026-11-21-social-login-connection.md) | 근거 정리·담당자 검토 전 |
| 2026-11-20 | [다른 공고의 조건 질문·답변 개별 전면 점검](entries/2026-11-20-all-notice-condition-audit.md) | 근거 정리·담당자 검토 전 |
| 2026-11-20 | [포항블루밸리 행복주택 조건 질문·답변 점검](entries/2026-11-20-pohang-condition-audit.md) | 근거 정리·담당자 검토 전 |
| 2026-11-16 | [수집 공고의 접수 시간과 회차별 일정](entries/2026-11-16-automatic-intake-times.md) | 검증 기록 |
| 2026-11-16 | [수집 공고의 상시 마감과 날짜 미확정 접수 시각](entries/2026-11-16-automatic-rolling-intake.md) | 확인 필요 |
| 2026-11-16 | [기존 수집 공고의 접수 시각·회차 재점검](entries/2026-11-16-current-intake-backfill.md) | 검증 완료 |
| 2026-11-16 | [Windows 개발 환경의 Java 서식 검사](entries/2026-11-16-java-formatter-local.md) | 근거 정리·담당자 검토 전 |
| 2026-11-16 | [관심 공고에서 저장한 두 공고 비교](entries/2026-11-16-saved-notice-comparison.md) | 확인 필요 |
| 2026-11-16 | [관심 공고 카드 미리보기](entries/2026-11-16-saved-notice-preview.md) | 근거 정리·담당자 검토 전 |
| 2026-11-15 | [접수 중·예정 공고의 조건과 정정 근거 재검토](entries/2026-11-15-active-conditions-review.md) | 확인 필요 |
| 2026-11-15 | [준비 기록의 중복 보관과 복원 후 오래된 저장 방지](entries/2026-11-15-archive-revision.md) | 근거 정리·담당자 검토 전 |
| 2026-11-15 | [신규 모집의 정정 대조와 조건 요약](entries/2026-11-15-automatic-conditions-review.md) | 확인 필요 |
| 2026-11-15 | [공고 상세 조건 카드의 기본 요약 축소](entries/2026-11-15-notice-condition-preview.md) | 확인 필요 |
| 2026-11-12 | [접수 종료 후 28일 이내 일부 확인 필요 7건 재검토](entries/2026-11-12-closed-seven-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-12 | [검수 대기·첨부 읽기 오류 36건 원문 대조](entries/2026-11-12-guidance36-source-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [첨부 신청기간 자동 반영과 오래된 미종료 공고 보완](entries/2026-11-11-attachment-closure.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [신규 수집 공고의 공식 정정 관계 자동 연결](entries/2026-11-11-automatic-correction-link.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [기존 정정 공고 대조와 기본 목록 28일 범위](entries/2026-11-11-correction-backfill-list-window.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [정정 공고가 있는 원공고 목록 제외](entries/2026-11-11-correction-list.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [가이드 전체 로딩으로 인한 탭 전환 지연 수정](entries/2026-11-11-guide-loading-pagination.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [파주운정 A23·충주안림1 첨부 접수기간 반영](entries/2026-11-11-legacy-application-closure.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [구형 공식 정정 이력 6건 연결](entries/2026-11-11-legacy-six-corrections.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [공고의 LH 기관 표시를 공식 CI로 변경](entries/2026-11-11-lh-agency-ci.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [공고·내 준비 목록 로딩 데이터 경량화](entries/2026-11-11-list-loading-payloads.md) | 근거 정리·담당자 검토 전 |
| 2026-11-11 | [남은 구형 정정 3건의 공식 첨부 대조](entries/2026-11-11-remaining-three-corrections.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [미종료 공고의 개별 결과 안내 대조·반영](entries/2026-11-10-active-individual-announcements.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [미종료 공고 12개 쟁점 재대조와 확인 범위 축소](entries/2026-11-10-active12-recheck.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [새 수집 공고의 서류마감 자동 연결과 접수종료 제외](entries/2026-11-10-automatic-document-deadlines.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [거창 신원면 공고 서류마감 11/13 반영](entries/2026-11-10-geochang-document-deadline.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [확정 날짜 없는 결과 발표를 개별 안내로 표시](entries/2026-11-10-individual-announcement.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [남원노암 발표일 차이 공식 자료 재대조](entries/2026-11-10-namwon-date-recheck.md) | 근거 정리·담당자 검토 전 |
| 2026-11-10 | [공고별 지원 대상·지원 내용을 2~3문장으로 표시](entries/2026-11-10-notice-support-summary.md) | 근거 정리·담당자 검토 전 |
| 2026-11-09 | [신규 공고의 정정·후속 공식 자료 자동 대조](entries/2026-11-09-automatic-followup-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-09 | [접수 종료 제외 후 미종료 공고 10건 준비 안내 반영](entries/2026-11-09-pending-active-guidance.md) | 근거 정리·담당자 검토 전 |
| 2026-11-09 | [남은 확인 항목을 정정본·후속 공식 자료로 해소](entries/2026-11-09-review-resolution.md) | 근거 정리·담당자 검토 전 |
| 2026-11-08 | [읽기 실패 복구와 최신 공고 준비 안내 반영](entries/2026-11-08-current-guidance-reading-recovery.md) | 확인 필요 |
| 2026-11-08 | [스캔 원문 오독 대조와 확인된 조건·서류 안내 제공](entries/2026-11-08-ocr-guidance-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-08 | [기존 보류 18건 재검수와 접수 중 우선 정렬](entries/2026-11-08-public18-review-and-notice-sort.md) | 근거 정리·담당자 검토 전 |
| 2026-11-08 | [공개 보류 18건의 공고별 원문 대조와 준비 안내 반영](entries/2026-11-08-public18-source-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-08 | [남은 보류 5건 최신 원문 재확인과 정정본 연결](entries/2026-11-08-remaining5-corrections.md) | 근거 정리·담당자 검토 전 |
| 2026-11-08 | [검수 중 공고의 공식 원문 열람](entries/2026-11-08-reviewing-source-information.md) | 근거 정리·팀 검토 전 |
| 2026-11-08 | [스캔 PDF와 도면의 원문 판독 복구](entries/2026-11-08-scanned-source-ocr.md) | 검증 결과 기록 완료 |
| 2026-11-08 | [익산한스빌 유형별 준비 안내 초안과 출처 검토 보완](entries/2026-11-08-typed-guidance-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [화면·기능을 보존한 구현 경계 정리](entries/2026-11-07-code-boundaries.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [라이트·다크 화면 모드](entries/2026-11-07-color-mode.md) | 확인 필요 |
| 2026-11-07 | [진행 중인 조건 질문 이어가기](entries/2026-11-07-condition-resume.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [출시 준비: 수집·접수 시각·근거·인증·알림 연결 보완](entries/2026-11-07-launch-integrations.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [로그인 하단 체험·계정 링크 영역 정리](entries/2026-11-07-login-footer.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [로그인 입력과 보조 동작의 시각적 구분](entries/2026-11-07-login-ui.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [준비 기록 삭제 팝업 UI 정리](entries/2026-11-07-preparation-delete-dialog.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [내 준비 공고 미선택 화면 정리](entries/2026-11-07-preparation-empty-state.md) | 근거 정리·담당자 검토 전 |
| 2026-11-07 | [공식 종료 상태·일회 확인·수집 원본과 운영 준비](entries/2026-11-07-readiness-operations.md) | 근거 정리·담당자 검토 전 |
| 2026-11-06 | [공식 첨부 내용 변경 감지와 후속 결정표](entries/2026-11-06-attachment-change-detection.md) | 근거 정리·담당자 검토 전 |
| 2026-11-06 | [예약 수집·원문 발췌·인증과 준비 기록 보완](entries/2026-11-06-collection-security-inputs.md) | 근거 정리·담당자 검토 전 |
| 2026-11-06 | [이전 준비 기록·계정 연결·접근성·상세 비교 보완](entries/2026-11-06-history-connections-accessibility-comparison.md) | 근거 정리·담당자 검토 전 |
| 2026-11-06 | [현재 코드 기반 리팩토링 후보 검토](entries/2026-11-06-refactoring-candidates.md) | 9개 후보 구현·검증 완료. 후속 사용자 재시작 요청으로 최신 서버까지 적용 완료. |
| 2026-11-06 | [로컬 모바일 구현을 팀 검토용 브랜치로 공유](entries/2026-11-06-team-review-upload.md) | 근거 정리·담당자 검토 전 |
| 2026-11-04 | [로그인 없이 신청 준비를 체험하는 로컬 세션](entries/2026-11-04-guest-trial.md) | 실행 근거 정리·담당자 검토 전 |
| 2026-11-04 | [모바일 홈 조정과 참고 이미지 기반 벽돌 진행도](entries/2026-11-04-mobile-home-brick-progress.md) | 근거 정리·담당자 검토 전 |
| 2026-11-04 | [공고 상세 시안 반영과 표시 책임 분리](entries/2026-11-04-notice-detail-reference.md) | 근거 정리·담당자 검토 전 |
| 2026-11-04 | [참고 시안에 맞춘 공고 목록 화면 정리](entries/2026-11-04-notice-list-reference.md) | 근거 정리·담당자 검토 전 |
| 2026-11-04 | [내 준비 개요 이미지 반영과 표시 모듈 분리](entries/2026-11-04-preparation-overview-reference.md) | 근거 정리·담당자 검토 전 |
| 2026-11-04 | [선택한 공고 기준으로 홈 보기](entries/2026-11-04-selected-notice-home.md) | 실행 근거 정리·담당자 검토 전 |
| 2026-11-03 | [Windows에서 GitHub 사본을 실행하고 공고 목록 확인](entries/2026-11-03-local-windows-run.md) | 근거 정리·담당자 검토 전 |
| 2026-11-03 | [공고 탐색에서 개인 준비 기록까지 이어지는 흐름](entries/2026-11-03-miro-preparation-workflow.md) | 근거 정리·담당자 검토 전 |
| 2026-11-03 | [모바일 화면 10종과 로컬 브라우저 검증](entries/2026-11-03-mobile-screen-references.md) | 근거 정리·담당자 검토 전 |
| 2026-11-03 | [준비 중 메뉴를 현재 사이트의 실제 기능으로 전환](entries/2026-11-03-record-based-feature-port.md) | 실행 근거 정리·담당자 검토 전 |
| 2026-11-01 | [GitHub Pages 팀 화면 테스트용 시연 배포](entries/2026-11-01-github-pages-demo.md) | 근거 정리·담당자 검토 전 |
| 2026-11-01 | [LH 원문 준비 안내 검수와 조건부 서류 연결](entries/2026-11-01-official-guidance-review.md) | 근거 정리·담당자 검토 전 |
| 2026-11-01 | [실제 LH 공고 기반 공고 찾기 전환](entries/2026-11-01-official-notice-catalog.md) | 근거 정리·담당자 검토 전 |
| 2026-11-01 | [질문별 답변과 준비 대상 서류 필터](entries/2026-11-01-question-options-documents.md) | 근거 정리·담당자 검토 전 |
| 2026-11-01 | [README를 현재 구현·시연 배포 상태에 맞게 정리](entries/2026-11-01-readme-refresh.md) | 근거 정리·담당자 검토 전 |
| 2026-11-01 | [모바일·PC 시안 기반 프론트엔드 재디자인](entries/2026-11-01-responsive-redesign.md) | 검증 완료 |
| 2026-10-31 | [외부 로그인 및 LH 실서비스 연동 검증](entries/2026-10-31-external-integration-verification.md) | 근거 정리·담당자 검토 전 |
| 2026-10-31 | [개인 VS Code 설정의 Git 추적 제외](entries/2026-10-31-ignore-vscode.md) | 근거 정리·담당자 검토 전 |
| 2026-10-31 | [백엔드 시작 실패: 중지된 PostgreSQL 복구](entries/2026-10-31-local-db-startup.md) | 근거 정리·담당자 검토 전 |
| 2026-10-31 | [최신 develop 동기화와 로컬 기록 보존](entries/2026-10-31-local-develop-sync.md) | 근거 정리·담당자 검토 전 |
| 2026-10-31 | [화면 설계 이미지 10개 갱신](entries/2026-10-31-screen-mockups.md) | 근거 정리·담당자 검토 전 |
| 2026-10-30 | [백엔드 구현과 실제 API 연동](entries/2026-10-30-backend-integration.md) | 근거 정리·담당자 검토 전 |
| 2026-10-30 | [누적 PR 검토와 develop 통합 검증](entries/2026-10-30-develop-integration-review.md) | 근거 정리·담당자 검토 전 |
| 2026-10-30 | [프론트엔드 전반 구현과 기능 계약 구체화](entries/2026-10-30-frontend-implementation.md) | 근거 정리·담당자 검토 전 |
| 2026-10-30 | [구현 착수 준비 상태와 프론트엔드·백엔드 진행 순서 검토](entries/2026-10-30-implementation-readiness.md) | 근거 정리·담당자 검토 전 |
| 2026-10-29 | [초기 개발 환경 구성과 기존 DB 환경 변수 충돌 해결](entries/2026-10-29-initial-setup.md) | 근거 정리·담당자 검토 전 |
| 2026-10-29 | [발표·포트폴리오에 활용할 프로젝트 기록 자동화](entries/2026-10-29-project-history.md) | 근거 정리·담당자 검토 전 |
<!-- PROJECT-HISTORY:END -->
