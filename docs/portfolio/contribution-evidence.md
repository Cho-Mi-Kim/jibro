# 김윤정 기여 근거와 PR 대조표

최초 조사 기준일: 2026-11-16 · 공유 전 보완: 2026-11-25\
본문: [이력서·포트폴리오 기여 정리](kim-yunjeong-contributions.md)

## 조사 범위와 해석

- 이 대화의 사용자 요청·확인 답변, 저장소 작업 기록, GitHub PR #1~#30의 제목·본문·작성자·head·병합 상태를 대조했다.
- 연결 계정은 jyk3716이다. 이 계정의 저장소 PR 28개와 별도 조회한 #24·#26을 합쳐 30개를 확인했다. 이는 활동 목록이며 개인이 개발한 기능 수가 아니다.
- #1·#12·#16·#20·#24·#26·#29의 변경 파일 목록을 확인했다. #12 LhNoticeClient와 #29 통합 테스트의 원격 패치, #24·#26의 후속 코멘트, 후속 수정 커밋의 로컬 변경 목록을 대조했다. 모든 파일을 새로 전체 코드 리뷰한 것은 아니다.
- PR #24·#26의 작성자는 kangjoonkim이다. 이름·계정 연결이나 팀원별 직접 코딩 비율은 추정하지 않는다.
- 코드 작성·오류 조사·검사를 Codex가 수행했다고 명시한 기록을 개인의 수작업 수행 이력으로 바꾸지 않았다. jyk3716 이름의 PR 코멘트도 AI가 사용자의 요청으로 게시한 기록일 수 있으므로 같은 기준을 적용한다.
- 이번 조사에서는 제품 테스트, 실제 로그인, LH API 수집 또는 Pages 브라우저 회귀 검사를 새로 실행하지 않았다. 아래 수치는 해당 일자의 실행 기록을 인용한 것이다.
- 최초 조사 당시 로컬 기준은 develop의 PR #30 반영 커밋 db9240c, main 제품 반영 기준은 PR #28의 19c1a4f였다. 현재 최신 상태라는 뜻은 아니다. 공유 전 작업 기준은 develop `0c91949`이며 [11월 25일 동기화 기록](../history/entries/2026-11-25-local-develop-sync.md)을 따른다. 아래 PR 표는 최초 조사 범위 #1~#30을 유지한다.
- 과거 기록의 ‘후속 작업’과 현재 상태가 다르면 후속 PR·기록을 함께 읽었다. 팀 컨벤션의 ‘팀명 미정’ 등 오래된 표기는 최신 사용자 제공 팀 정보로 보완했다. 정기회의·멘토링·업무 배분을 실제 수행한 사실로 추가하지 않았다.
- Notion 회의록은 이번 조사에서 조회하지 않았다. 실제 사용자 인터뷰·사용성 조사·기획 효과 수치를 보충하지 않았다.
- 2026-11-25 공유 전 검토에서는 PR #24의 본문·수정 코멘트를 다시 읽고 사용자 후속 설명을 반영했다. 검토 동기는 인수인계 전 데이터 처리·계약 정합성에 대한 우려였으며, 수정 후 두 탭 직접 재검증과 수정 코드 검토를 수행했다고 사용자가 밝혔다. 이 동기·직접 행동의 근거는 사용자 설명이고, 최초 버그 발견·원인 분석·수정 근거는 AI 작업 기록과 PR 코멘트다.
- [11월 22일 AWS 배포](../history/entries/2026-11-22-aws-free-deployment.md) 및 [11월 24일 운영 대조](../history/entries/2026-11-24-final-production-verification.md) 기록을 확인했다. 과거의 운영 미검증 상태를 현재 미배포로 단정하지 않으며, 후속 운영 작업을 개인 성과로 편입하지 않는다.

## 기여 수준 구분

| 구분 | 의미 | 예 |
| --- | --- | --- |
| 본인 결정 | 사용자 발언과 기록에 요구·선택이 명시됨 | Gradle 변경, Google·카카오 모두 선택, 질문별 답변, 준비 대상 서류 노출, 브랜치별 PR 규칙 |
| 본인 직접 확인·설정 | 사용자 본인이 실행·결과를 보고함 | 카카오 공인 IP 설정, Google Edge 로그인 유지·로그아웃, PR #24 두 탭 메모 재검증·수정 코드 검토, develop 화면 확인 |
| AI 보조 개발·관리 | 사용자 진행 범위 안에서 AI가 구현·조사·검사·공유함 | 초기 풀스택 구현, R1/R2 수정, PR 충돌·CI 실패 분석, 문서·배포 |
| 다른 계정의 기능 확장 | 타 계정 PR의 원래 구현 | #24 회원·준비 흐름 확장, #26 공고 원문·자동화·UX 확장 |
| 미확인 | 직접 수행·결과 근거 부족 | 수작업 코드 비율, 개인 개발 기여율, 전체 사용자 만족도, 후속 AWS 배포의 개인 담당 범위, 외부 제출 완료 |

## PR별 대조 목록

모두 조사 시점에 병합 상태임을 원격 메타데이터로 확인했다. main 반영·문서 공유 PR을 별도 제품 기능 실적으로 중복 계산하지 않는다.

| PR | 제목 | PR 작성 계정 | 본인 기여로 설명할 범위 | 상태 |
| --- | --- | --- | --- | --- |
| [#1](https://github.com/Cho-Mi-Kim/jibro/pull/1) | feat: 프로젝트 작업 기록 자동화 추가 | jyk3716 | 기록 자동화 목적 제시·AI 보조 구현 | 병합 |
| [#2](https://github.com/Cho-Mi-Kim/jibro/pull/2) | chore: 작업 기록 자동화를 main에 반영 | jyk3716 | 동일 기록 기능 main 반영 | 병합 |
| [#3](https://github.com/Cho-Mi-Kim/jibro/pull/3) | docs: 구현 준비 점검과 개발 순서 정리 | jyk3716 | 구현 순서·범위 점검 요청 | 병합 |
| [#4](https://github.com/Cho-Mi-Kim/jibro/pull/4) | feat: 프론트엔드 계약과 공통 UI 기반 | jyk3716 | 기능 계약 기록·초기 프론트 개발 진행 | 병합 |
| [#5](https://github.com/Cho-Mi-Kim/jibro/pull/5) | feat: 공고 탐색부터 개인 준비까지 화면 흐름 구현 | jyk3716 | 초기 사용자 흐름 AI 보조 구현 | 병합 |
| [#6](https://github.com/Cho-Mi-Kim/jibro/pull/6) | feat: 프론트엔드 시연 검증과 반응형 보완 | jyk3716 | 초기 화면·시연 검증 진행 | 병합 |
| [#7](https://github.com/Cho-Mi-Kim/jibro/pull/7) | feat: 계정과 JWT 인증 및 OAuth 연결 기반 | jyk3716 | 인증 방식 선택·AI 보조 구현 | 병합 |
| [#8](https://github.com/Cho-Mi-Kim/jibro/pull/8) | feat: 공고 검색과 개인 준비 저장 및 검수 | jyk3716 | 공고·준비·검수 초기 구현 진행 | 병합 |
| [#9](https://github.com/Cho-Mi-Kim/jibro/pull/9) | feat: LH 공고 수집과 원본 변경 감지 | jyk3716 | 자동 수집 방식 선택·초기 구현 진행 | 병합 |
| [#10](https://github.com/Cho-Mi-Kim/jibro/pull/10) | feat: 백엔드와 프론트엔드 실제 연동 완성 | jyk3716 | 실제 API 연동 진행 | 병합 |
| [#11](https://github.com/Cho-Mi-Kim/jibro/pull/11) | fix: 검증 서버 재시작과 develop 통합 기록 마무리 | jyk3716 | 통합 확인·실행 보완 | 병합 |
| [#12](https://github.com/Cho-Mi-Kim/jibro/pull/12) | [fix] LH 실서비스 수집 및 소셜 로그인 진단·검증 보완 | jyk3716 | 외부 설정·실제 로그인 확인 및 AI 보조 오류 수정 | 병합 |
| [#13](https://github.com/Cho-Mi-Kim/jibro/pull/13) | [docs] 화면 설계 이미지 10개 갱신 | jyk3716 | 화면 참고 자료 반영 | 병합 |
| [#14](https://github.com/Cho-Mi-Kim/jibro/pull/14) | [chore] 개인 VS Code 설정 추적 제외 | jyk3716 | 개인 설정 제외 | 병합 |
| [#15](https://github.com/Cho-Mi-Kim/jibro/pull/15) | [docs] PR 검토·병합 및 로컬 동기화 기록 공유 | jyk3716 | 기록 보존·공유 | 병합 |
| [#16](https://github.com/Cho-Mi-Kim/jibro/pull/16) | feat: 반응형 재디자인과 실제 LH 공고·맞춤 준비 안내 통합 | jyk3716 | 반응형·공고·질문/서류 UX 요구 및 AI 보조 구현 | 병합 |
| [#17](https://github.com/Cho-Mi-Kim/jibro/pull/17) | design: 집 벽돌을 층별로 엇갈리게 배치 | jyk3716 | 벽돌·박공 표현 피드백 반영 | 병합 |
| [#18](https://github.com/Cho-Mi-Kim/jibro/pull/18) | docs: 브랜치별 PR 원칙과 검토·병합 기록 공유 | jyk3716 | 작업 브랜치별 PR 원칙 확정 | 병합 |
| [#19](https://github.com/Cho-Mi-Kim/jibro/pull/19) | feat: 집으로 서비스 구현·실제 연동·반응형 UI를 main에 반영 | jyk3716 | 누적 구현 main 반영 | 병합 |
| [#20](https://github.com/Cho-Mi-Kim/jibro/pull/20) | feat: 팀 화면 테스트용 GitHub Pages 시연 배포 | jyk3716 | 서버 없는 팀 시연 선택·배포 구현 진행 | 병합 |
| [#21](https://github.com/Cho-Mi-Kim/jibro/pull/21) | release: GitHub Pages 팀 시연 공개 | jyk3716 | 동일 시연 main 공개 | 병합 |
| [#22](https://github.com/Cho-Mi-Kim/jibro/pull/22) | docs: 현재 기능과 Pages 시연 기준으로 README 최신화 | jyk3716 | README·검증 기록 최신화 | 병합 |
| [#23](https://github.com/Cho-Mi-Kim/jibro/pull/23) | docs: README 최신화와 검증 기록을 main에 반영 | jyk3716 | 동일 문서 main 반영 | 병합 |
| [#24](https://github.com/Cho-Mi-Kim/jibro/pull/24) | 모바일 화면·준비 흐름·회원 기능 및 코드 모델 검토 | kangjoonkim | 타 계정의 기능 확장에 후속 수정·계약 대조·화면 확인·통합 | 병합 |
| [#25](https://github.com/Cho-Mi-Kim/jibro/pull/25) | release: 모바일 준비 흐름과 회원 기능을 main 및 Pages에 반영 | jyk3716 | PR #24 main 반영·Pages 확인 | 병합 |
| [#26](https://github.com/Cho-Mi-Kim/jibro/pull/26) | 공고 탐색·공식 원문 검수·신청 준비 흐름 개선 | kangjoonkim | 타 계정의 확장에 충돌·중복 검사 및 CI 후속 수정·통합 | 병합 |
| [#27](https://github.com/Cho-Mi-Kim/jibro/pull/27) | docs: PR 26 기준 개발 인수인계 및 검증 기록 최신화 | jyk3716 | 최신 구현 기준 인수인계 정리 | 병합 |
| [#28](https://github.com/Cho-Mi-Kim/jibro/pull/28) | release: 공고 탐색·준비 흐름 및 최신 문서 반영 | jyk3716 | develop → main 반영·Pages 검증 | 병합 |
| [#29](https://github.com/Cho-Mi-Kim/jibro/pull/29) | test: 공고 첨부 통합 테스트의 UTC·한국 날짜 경계 수정 | jyk3716 | 배포 검증 중 발견한 날짜 fixture 오류 AI 보조 수정 | 병합 |
| [#30](https://github.com/Cho-Mi-Kim/jibro/pull/30) | docs: main 배포 및 develop 동기화 검증 기록 정리 | jyk3716 | 배포·동기화 사후 기록 | 병합 |

**PR #16의 이력:** 당시 반응형 재디자인·실제 공고 목록·공식 안내 검수·질문/서류 변경이 하나의 PR에 합쳐졌다. 사용자는 이후 이를 아쉬워하며 브랜치마다 PR을 만들도록 명시했다. “처음부터 모든 작업을 독립 PR로 운영했다”가 아니라 “통합 PR의 한계를 경험한 뒤 브랜치별 PR 원칙을 보완했다”로 설명한다. 근거는 [PR #18](https://github.com/Cho-Mi-Kim/jibro/pull/18)과 [현재 작업 지침](../../AGENTS.md)이다.

## 사례별 로컬 근거

| ID | 주제·원문 | 확인되는 내용·한계 |
| --- | --- | --- |
| E01 | [초기 환경](../history/entries/2026-10-29-initial-setup.md) | 기술 선택·기존 DB 환경 변수 충돌·환경 검증. 초기 기능 준비와 완성 상태를 구분 |
| E02 | [기록 자동화](../history/entries/2026-10-29-project-history.md) | 사용자가 공유·포트폴리오 목적 제시. AI 구현·7개 검사. 수작업 코딩 증거 아님 |
| E03 | [초기 프론트](../history/entries/2026-10-30-frontend-implementation.md) | 계약·모의 어댑터·사용자 흐름 및 AI 구현 범위 |
| E04 | [초기 백엔드](../history/entries/2026-10-30-backend-integration.md) | 인증·공고·준비 저장·검수·수집·연동. 상세 구현·검사는 AI 보조 |
| E05 | [외부 연동](../history/entries/2026-10-31-external-integration-verification.md) | 직접 외부 설정·계정 동의·Google Edge 유지/로그아웃 확인과 AI 서버 검증 구분 |
| E06 | [반응형 디자인](../history/entries/2026-11-01-responsive-redesign.md) | 사용자가 제공한 참고 화면·피드백, 실제 데이터 유지·뷰포트 검사 |
| E07 | [공고·안내 검수](../history/entries/2026-11-01-official-guidance-review.md) | 공고 기본 정보와 준비 안내 공개 경계. AI 문서 대조를 사람 전문가 검수로 표현하지 않음 |
| E08 | [질문·대상 서류](../history/entries/2026-11-01-question-options-documents.md) | 사용자의 명시적 답변 형태·서류 노출 요구, AI 구현·회귀 검증 |
| E09 | [정적 시연](../history/entries/2026-11-01-github-pages-demo.md) | 사용자 선택·AI 구현 및 실제 서버와 다른 검증 범위 |
| E10 | [PR #24 후속 검토](../history/entries/2026-11-10-pr24-contract-review.md) | AI의 R1/R2 발견·수정과 사용자 디자인 확인·병합 재개 분리. 두 탭 직접 재검증·코드 검토 및 검토 동기는 후속 사용자 설명으로 보완 |
| E11 | [PR #26 충돌](../history/entries/2026-11-15-pr26-conflict-resolution.md) | 8개 충돌·중복 테스트·OCR 테스트 경합. 자동 검사 통과를 직접 코드 작성으로 해석하지 않음 |
| E12 | [인수인계](../history/entries/2026-11-15-handoff-docs-review.md) | 최신 구현과 과거 초안 대조, 개발 완료와 운영 검증 과제 분리 |
| E13 | [시간대·배포](../history/entries/2026-11-16-main-pages-release.md) | UTC/KST fixture 문제·main CI·Pages 확인. 제품 코드 변경 아님 |
| E14 | [포스터](../history/entries/2026-11-16-poster-print-captures.md) | 사용자 문안·디자인 피드백과 AI 캡처·제작·검수. 최초 조사 당시 미커밋이었고 이번 문서 공유에 포함. 실제 제출 미확인 |

추가 기준 문서: [기획안](../planning/project-proposal.md), [코딩 컨벤션](../conventions/coding-conventions.md), [프론트 계약](../architecture/frontend-contracts.md), [백엔드 계약](../architecture/backend-contracts.md), [질문·서류 계약](../contracts/question-options-and-documents.md), [개발 인수인계](../developer-handoff.md).

## 기술 사례를 추적할 커밋·패치

| 근거 | 무엇을 확인했는가 | 개인 기여로 옮길 때의 주의 |
| --- | --- | --- |
| [PR #12](https://github.com/Cho-Mi-Kim/jibro/pull/12/files), LhNoticeClient.java 패치 | PAN_NT_ST_DT·CLSG_DT 대신 PAN_ST_DT·PAN_ED_DT 사용, BASIC_ISO_DATE·Asia/Seoul 반영 | 요청 인자 오류 수정은 AI 조사·구현. 본인의 외부 설정·확인과 구분 |
| [2918a43](https://github.com/Cho-Mi-Kim/jibro/commit/2918a43b8c737719cc7f07b175920888caf24afa) | PR #24 보관 revision·메모 보호, 시연 지연 분리, 회귀 검사 | PR #24 전체 기능 개발이 아니라 후속 보완 |
| [PR #24 수정 코멘트](https://github.com/Cho-Mi-Kim/jibro/pull/24#issuecomment-6760142110) | 재현 문제·수정·로컬 검사와 CI를 한곳에서 확인 | 게시 계정이 인간 단독 분석을 증명하지 않음 |
| [PR #24 실행 문서 코멘트](https://github.com/Cho-Mi-Kim/jibro/pull/24#issuecomment-6760422771) | 시연/실서버 실행·버튼 차이·환경 안내 | 실제 OAuth 왕복을 그날 재검증한 것은 아님 |
| [69fd749](https://github.com/Cho-Mi-Kim/jibro/commit/69fd7496f91e247c1facc5f42c74642a04dbc793) | OCR 테스트 worker의 Linux 종료 경합 보완 | OCR 제품 기능 전체 또는 제품 프로세스 결함 수정으로 확대하지 않음 |
| [PR #26 해결 코멘트](https://github.com/Cho-Mi-Kim/jibro/pull/26#issuecomment-6896119283) | 충돌·중복 테스트·구조 보존 및 검사 범위 | 확장 기능의 원래 작성과 통합 작업을 분리 |
| [PR #29](https://github.com/Cho-Mi-Kim/jibro/pull/29/files) | UTC DB 세션·한국 기준 날짜·미래 fixture의 명시 | 제품 공고 상태 계산을 바꾼 수정이 아님 |

## 인용 가능한 검사 기록

| 시점 | 확인 결과 | 범위·한계 |
| --- | --- | --- |
| 2026-10-31, #12 | LH 수집 각 276건, 두 번 실행 후 DB 276건 유지 | 당시 실제 요청 기간·환경의 결과. 전부 검수·공개 완료 아님 |
| 2026-11-01, #16 | 질문 7개 사례에서 대상 서류 5개 노출·비대상 4개 숨김 | 단일 공고·예시 조건. 사용자 효과·정확도 통계 아님 |
| 2026-11-10, #24 후속 수정 | 프론트 134건, 백엔드 단위 15건·DB 통합 25건 통과 | 팀 코드 전체 검사. 본인 테스트 작성 건수 아님 |
| 2026-11-15, #26 | 로컬 프론트 419건·백엔드 단위/구조 668건·통합 358건, 후속 OCR 관련 5건 통과 | 서로 다른 검사 실행이며 5건을 전체에 더해 독립 성과로 계산하지 않음. 실제 외부 제공자 왕복 제외 |
| 2026-11-16, #29 | UTC JVM·PostgreSQL에서 해당 통합 테스트 1건 재검증 | 시간대 fixture 문제 재현·수정 범위 |
| 2026-11-16, #28 | main CI 4개 작업, Pages build·deploy 성공 및 핵심 시연 흐름 확인 | 가상 데이터 프론트 시연. 운영 API·DB 배포 아님 |

원격 기록: [#24 수정 CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/42764598416), [#26 최종 CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43724321929), [#28 최종 CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43731112607), [Pages 배포](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43731519733). 해당 링크는 PR 코멘트·작업 기록과 대조했으며 이번 조사에서 CI를 새로 실행하지 않았다.

## 제출 직전 사용할 기준

- 이력서에서는 제품 요구사항·선택·확인이라는 본인 역할을 앞에 두고 AI 보조 개발 범위를 함께 적는다.
- 기술 사례는 직접 설명 가능한 것만 사용한다. 원인 분석을 AI가 수행했다면 그대로 밝힌다.
- PR 수·변경 줄 수로 개발 기여율을 산정하지 않는다.
- 초기 구현과 팀원 확장, 실제 서버 확인과 정적 시연, 코드 구현과 운영 검증을 구분한다.
- E14 포스터 기록은 최초 조사 당시 미커밋이었으며 이번 문서 공유에 포함한다. 최종 산출물과 외부 제출 여부는 별개이며 본 문서로 제출 완료를 인증하지 않는다.
