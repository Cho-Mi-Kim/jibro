# 공고 탐색·회원 기능 구현 계약

최초 적용일은 2026-11-03, 최종 코드 대조일은 2026-11-10이다. PR #24 (`feature/local-mobile-screens-20261103`)의 계약이며 develop/main 병합·운영 검증 완료를 뜻하지 않는다. 사용자가 이전 Codex 작업의 기능을 현재 사이트에 이식하고 준비 중이던 항목을 구현하도록 범위를 변경했다. 이 문서는 화면설계서 7.1의 항목별 연결 범위를 정한다. 초기 구현 `e288a99`에 대한 지적과 후속 수정은 [검토 기록](../history/entries/2026-11-10-pr24-contract-review.md)에서 구분한다.

| 진입점 | 동작과 저장 경계 |
| --- | --- |
| 공고 카드·상세 → 관심 / 마이 → 관심 공고 | 공개된 공고만 로그인 계정에 저장한다. 저장 요청은 멱등이고 다른 계정의 목록은 조회할 수 없다. 상세 열람만으로 개인 준비 건을 생성하지 않는다. |
| 공고 목록·상세 → 공고 비교 | 공개 공고 두 건의 기관·지역·유형·기간·안내 상태·요약을 비교한다. URL에 선택이 유지되며 적합도·자격을 계산하지 않는다. |
| 공고 상세 → 수정 전후 비교 | 공개 당시 버전과 검수 리비전을 보관하고 두 판본의 달라진 항목만 표시한다. 비공개 공고는 접근할 수 없고 현재 안내가 검수 중이면 과거 질문·서류를 공개하지 않는다. 이전 공개본이 없으면 그 사실을 표시한다. |
| 가이드 → 검색 | 검색어와 카테고리를 URL에 유지한다. 한글 검색어를 공백 단위로 나누어 제목·본문·주제·근거에서 함께 찾는다. 공개 공고의 기본 정보와 검수된 질문·서류·일정으로 항목을 구성한다. |
| 내 준비 → 서류 → 발급 방법 보기 | 공고에서 확인한 방법·기관·근거와 검증된 외부 경로를 구분한다. 주소가 없으면 기관과 원문 확인 경로를 제공한다. 열람·외부 이동으로 서류 상태가 변하지 않는다. |
| 알림 → 수신 설정·공고 소식 | 기본값은 수신 해제다. 1·3·7일 범위에서 준비 공고 및 선택한 관심 공고의 공식 기한과 버전 변경을 조회한다. 목록·개별 읽음·모두 읽음은 계정별이다. 공개 중단·기한 변경 시 최신 공개 데이터에서 다시 계산한다. |
| 기한 알림 → 수신 방법 | 사이트를 열어 둔 동안 표시하는 기기 알림은 해당 브라우저의 명시적 권한을 사용한다. 확인한 이메일과 구독한 Web Push 기기에 대해 서버가 실행 중인 동안 별도로 발송한다. 이벤트·계정·채널별 전송 기록과 제한된 재시도를 둔다. |
| 공식 일정 → 캘린더 파일 | 선택한 공식 일정만 .ics로 내보낸다. 날짜만 있으면 종일 일정, 시각이 있으면 해당 시각을 보존한다. 안정된 UID·버전·생성 시각·공식 근거를 담는다. 개인 답변·메모·완료 상태는 제외한다. 외부 캘린더는 복사본이며 자동으로 변경·취소되지 않는다. |
| 마이·내 준비 → 완료 여정 보관함 | 직접 선택한 건을 보관하고 복원한다. 진행률이 100%라는 가정이나 실제 신청 완료 처리 없이 기존 서버 기록을 유지한다. 보관 중에는 수정이 차단된다. 본인이 남긴 경험 메모만 다른 진행 건에 명시적으로 연결한다. |
| 설정·로그인 | 닉네임·선택 관심 지역 수정, 이메일 확인, 현재 비밀번호로 변경, 메일 링크 재설정, 재확인 후 탈퇴를 제공한다. 메일 링크는 30분·일회용이고 서버에는 해시만 보관한다. 비밀번호 변경·재설정과 탈퇴 시 기존 세션을 무효화한다. 탈퇴 시 개인정보·준비 기록·수신 설정·구독을 지우고 검수 감사에 필요한 비식별 행만 유지한다. |

## 데이터와 API

Flyway V6은 관심 공고·알림 설정/읽음·보관 메모/참조·계정 상태/일회용 링크·공개본 이력을, V7은 메일 설정·푸시 구독·발송 기록을, V8은 공개본 상세 비교 정보를, V9는 체험 계정 만료 시각을 추가한다. 기존 준비 스냅샷 형식은 유지한다. 과거 로컬 백업 복원은 당시 작업 환경에서 수행한 일이며 다른 개발자의 DB나 운영 DB에 같은 데이터가 들어 있다고 가정하지 않는다. 마이그레이션은 모든 과거 공고를 소급 복원하지 않으며 저장된 공개본이 없는 경우 비교 불가를 표시한다.

공개 조회는 `GET /api/v1/guides`, `GET /api/v1/notices/{id}/versions`와 기존 공고 API를 사용한다. 계정 쓰기는 기존 Bearer 인증과 CSRF를 적용한다. `/api/v1/member` 아래에 `saved`, `alerts`, `alert-settings`, `channels`, `archive`, `preparations/{id}/references`, `profile`, `password`, `verify`, `closure`가 있다. 계정 링크는 `/auth/reset/request`, `/auth/reset/complete`, `/auth/verify`로 처리한다. 요청 화면에서 등록 여부를 노출하지 않는다.

### API 입출력

아래 경로는 `/api/v1` 기준이다. 회원 API는 본인 범위이며 변경 요청은 CSRF가 필요하다. 소유권 위반·비공개 조회는 404, 인증 실패는 401, 권한·CSRF 실패는 403을 기본으로 한다. 본문 없는 회원 변경 응답은 HTTP 200 빈 본문이며 로그아웃의 204와 구분한다.

| 요청 | 입력·응답 |
| --- | --- |
| `GET /member/saved` | `{id,title,agency,region,endDate}[]`; 현재 공개된 항목만 반환 |
| `PUT /member/saved/{id}`, `DELETE /member/saved/{id}` | 본문 없음. 저장 당시 버전을 유지하며 반복 저장·해제는 멱등 |
| `GET /member/alerts` | `{settings:{enabled,leadDays,includeSaved},items:[{key,noticeId,title,label,date,read}]}` |
| `PUT /member/alert-settings` | `{enabled,leadDays,includeSaved}`; leadDays는 1·3·7만 허용 |
| `PUT /member/alerts/read` | `{keys:string[]}`; 최대 1,000개, 현재 본인 알림에 있는 키만 반영. UI는 500개씩 분할 |
| `GET /member/channels` | `{emailEnabled,emailVerified,mailMode,publicKey}`; publicKey는 VAPID 공개키 또는 null |
| `PUT /member/channels/email` | `{enabled}`; 확인되지 않은 이메일·발송 불가 환경에서 활성화는 409 |
| `PUT /member/channels/push` | `{endpoint}`; 지원하는 HTTPS 푸시 제공자 주소만 허용, 서버 키가 없으면 503 |
| `POST /member/channels/push/status`, `POST /member/channels/push/remove` | `{endpoint}`; 본인 구독 여부 boolean 조회 / 본인 구독 삭제 |
| `GET /member/archive` | `{id,title,version,progress,note,archivedAt}[]` |
| `PUT /member/archive/{id}` | `{revision,note}`; note 최대 500자. 진행 중 상태와 현재 revision을 대조한 뒤 보관·메모 저장, revision 증가. 이미 보관된 건·오래된 revision은 409 |
| `DELETE /member/archive/{id}` | 진행 중으로 복원하고 해당 기록을 출처로 하는 메모 연결 삭제, revision 증가. 이미 진행 중이면 변경 없는 성공, 없는 건·타인 건은 404 |
| `GET /member/preparations/{id}/references` | 본인 대상에 연결된 본인의 보관 기록 배열 |
| `PUT /member/preparations/{id}/references/{source}` | 본인 진행 건에 본인 보관 건을 연결. 같은 건·타인 건·잘못된 상태 거절 |
| `GET /member/profile`, `PUT /member/profile` | 조회/반환 `{name,email,region,verified,passwordLogin,mailMode}`; 수정 입력 `{name,region}` |
| `PUT /member/password` | `{current,password}`; 비밀번호 로그인 계정에서 현재 비밀번호 확인 후 변경 |
| `GET /auth/mail-mode` | `{mode:'local'|'smtp'|'unavailable'}` |
| `POST /member/verify`, `POST /member/closure/request` | 본문 없음. 이메일 확인 / 탈퇴 확인 링크 요청 |
| `POST /auth/reset/request` | `{email}`; 비밀번호 계정만 발급 대상, 등록 여부와 무관한 빈 응답 |
| `POST /auth/reset/complete`, `POST /auth/verify` | `{token,password}` / `{token}`; 만료·재사용 링크는 400 |
| `POST /member/closure/complete` | `{token,confirmation:'회원 탈퇴'}`; 로그인 계정과 링크 소유자가 일치해야 함 |
| `GET /notices/{id}/versions` | `{version,revision,facts}[]`, 최신순. 현재 준비 안내 미제공이면 과거 questions/documents/schedules도 응답에서 제외하는 현행 구현 |

### 저장·권한·동작 경계

- 보관은 답변·준비도·신청 기록을 완료로 바꾸지 않는다. 보관된 건은 진행 목록에서 제외하고 준비 조회·수정 경로로 편집할 수 없다. 같은 공고로 다시 시작하면 409 `ARCHIVED`로 복원을 안내한다. 실제 보관·복원 상태 전환마다 DB 컬럼과 JSON 스냅샷의 revision을 함께 1씩 증가시키고 updated_at을 갱신한다. 이미 보관된 기록의 재보관은 409로 거절해 메모를 보존한다. 이미 진행 중인 기록의 반복 복원은 revision을 올리지 않는다. 복원 후 수정·재보관은 새로 조회한 revision을 사용해야 하며, 시연 어댑터도 같은 규칙을 적용한다.
- 알림은 저장된 영구 알림 게시물이 아니라 현재 공개 공고·진행 준비·선택한 관심 공고에서 계산한다. 기본 수신은 꺼짐, 기본 범위는 3일, 관심 공고 포함 기본값은 true다. 버전 변경과 오늘부터 leadDays 뒤까지의 일정을 표시하며 날짜가 지나거나 공개가 중단되면 목록에서 빠질 수 있다. 시각 단위 예약 발송은 아니며 하루 단위로 기한을 고른다.
- 서버 발송은 기본 최초 45초 뒤, 이후 60초 간격이다. 읽지 않은 이벤트만 처리하며 계정·이벤트·채널별 전송 상태를 저장한다. 실패는 최소 10분 간격, 최대 4회 시도한다. 404/410인 푸시 구독은 제거한다. 이 스케줄러는 LH 공고 수집을 실행하지 않는다.
- 사이트를 열어 둔 동안의 기기 알림, 서버 Web Push, 이메일은 별개다. 사용자가 명시적으로 브라우저 권한·구독을 승인해야 하며 일반 로그아웃 시 현재 브라우저의 푸시 구독 해제를 시도한다. 서버 Web Push는 본문 없는 요청이며 서비스 워커는 개인 공고명·답변 없는 공통 문구를 표시한다.
- 이메일 확인을 모든 로그인에 강제하지 않는다. 링크는 계정·용도별로 관리하며 1분 내 재요청은 새 링크를 만들지 않는다. 비밀번호 변경·재설정은 기존 refresh token과 계정 링크를 지우고 security_version을 올린다. 탈퇴는 개인 기록·연결·구독을 지우고 감사 기록의 참조 키를 위한 비식별 비활성 계정만 남긴다.
- 체험 API는 `local-sample`에서만 활성화되며 역할은 TRIAL, 유효 시간은 24시간이다. 체험 종료·만료 정리 시 계정과 준비 기록을 삭제한다. 외부 채널·메일 확인·비밀번호·탈퇴 링크 기능은 USER/ADMIN만 허용한다. 기존 계정과 소셜 계정 연결 기능은 없다.

### 시연과 실제 연동의 차이

Pages/`dev:demo`는 메모리 어댑터다. 관심 저장·보관·프로필·알림 설정과 캘린더 파일 생성은 체험할 수 있으나 외부 로그인, 실제 메일·푸시, 서버 영속 저장은 제공하지 않는다. 시연 알림은 신청 접수 마감 중심으로 단순화되어 서버의 전체 공식 일정·버전 변경 알림과 같지 않다. 이메일·비밀번호·탈퇴 처리는 실제 API 모드에서 확인한다. 캘린더 파일은 다운로드 복사본이며 기관 신청이나 캘린더 자동 동기화가 아니다.

## 이 컴퓨터의 발송 방식

- **로컬 메일** · 루프백 주소의 `local-sample` 프로필에서 SMTP가 없으면 실행 중인 backend 폴더 기준 `../.tools/account-mail`에 테스트용 .eml을 작성한다. Codex 설정의 이 사이트에서는 저장소 루트의 `.tools/account-mail/`이다. 개발 PC 소유자가 확인하는 테스트 메일이다. 이 폴더를 웹으로 제공하지 않는다.
- **SMTP** · 로컬 `.env`에 `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `JIBRO_MAIL_FROM`을 설정하고 API를 재시작한다. 기본은 STARTTLS 필수다. `JIBRO_SMTP_STARTTLS=false`, `JIBRO_SMTP_AUTH=false`는 암호화·인증이 없는 루프백 테스트 수신기를 사용할 때만 쓴다. 클라이언트 번들에 자격 증명을 넣지 않는다.
- **브라우저 푸시** · `JIBRO_VAPID_JWK`에는 서버용 P-256 개인 JWK, `JIBRO_PUSH_SUBJECT`에는 실제 운영자 연락 URI(mailto 또는 HTTPS)를 설정한다. 공개키만 클라이언트에 제공한다. 브라우저의 사용자 권한과 구독이 있어야 전송할 수 있다. 잠금 화면에는 공고명이나 개인 답변을 넣지 않는다. 설정이 없으면 사이트 내 알림과 열린 사이트 기기 알림을 사용할 수 있다.
- **시연 빌드** · 공개 Pages는 메모리 시연 계정으로 작동한다. 메일·외부 푸시를 발송하지 않는다. 실제 계정 기능은 로컬 API와 DB를 사용한다.

캘린더 형식은 [RFC 5545](https://www.rfc-editor.org/rfc/rfc5545), 브라우저 푸시는 [RFC 8030](https://www.rfc-editor.org/rfc/rfc8030) 및 [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292)를 따른다.
