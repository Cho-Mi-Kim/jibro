"""Render the reviewed code model as a self-contained, offline HTML document."""
from pathlib import Path
import html
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'frontend/public/jibro-code-model.html'
esc = html.escape
B = 'backend/src/main/java/org/jibro/'
F = 'frontend/src/'
M = 'backend/src/main/resources/db/migration/'
nodes = {}

def ref(path, symbol=None):
    """Resolve a reviewed symbol to its current source line, without stale line literals."""
    lines = (ROOT / path).read_text(encoding='utf-8-sig').splitlines()
    if symbol is None:
        return path
    matches = [i + 1 for i, line in enumerate(lines) if symbol in line]
    if len(matches) != 1:
        raise ValueError(f'{path}: expected one source location for {symbol!r}, found {len(matches)}')
    return f'{path}:{matches[0]}'

def register(key, title, description, refs):
    nodes[key] = dict(title=title, description=description, refs=[ref(item) if not re.search(r':\d+$', item) else item for item in refs])

register('user', '사용자 · User', '준비 기록과 관심 공고의 소유자. 가입 시 이름·이메일·비밀번호를 받고, 조건 답변은 계정 공통 프로필이 아닌 공고별 준비에 저장한다.', [F+'features/housing/types.ts', B+'auth/AuthService.java', F+'pages/AccountPages.tsx'])
register('notice', '공고 · Notice / Content', '공개 공고 응답 Notice와 내부 안내 원본 Content는 서로 다른 구조다. Content에는 질문·서류 템플릿·일정 템플릿·근거가 있다. 공개 여부와 준비 안내 여부를 별도로 판단한다.', [B+'notice/HousingModels.java', B+'notice/NoticeService.java', F+'features/housing/types.ts'])
register('prep', '사용자별 준비 · Preparation', '이 모델의 중심. 시작 당시 공고의 질문·서류·일정을 복사하고 개인 답변·메모·신청 기록을 보관한다. version은 공고 기준 버전, revision은 준비 기록의 수정 번호다.', [B+'notice/HousingModels.java', B+'preparation/PreparationService.java', F+'features/housing/types.ts'])
register('question', '질문 · Question', '질문 ID·제목·이유·선택지·출처. 선택지에는 UNKNOWN을 포함한다. 서버가 허용된 질문과 선택값인지 검증하며, 클라이언트가 자격 규칙을 새로 만들지 않는다.', [B+'notice/HousingModels.java', B+'notice/NoticeService.java', B+'preparation/PreparationService.java'])
register('answer', '조건 답변 · answers Map', '별도 Answer 엔티티나 테이블은 없다. Preparation.answers의 키는 질문 ID, 값은 선택지 값이다. 한 준비 건 안에서 질문마다 최대 하나의 저장 답변이 있다.', [B+'notice/HousingModels.java', B+'preparation/PreparationService.java', F+'pages/ConditionsPage.tsx'])
register('document', '개인별 서류 · Document', '공고의 DocumentTemplate으로부터 생성된다. 필요 여부 necessity와 준비 상태 state는 서로 다른 축이며, 파일 원본을 담는 객체가 아니다. 사용자 메모·발급 경로·근거를 보유한다.', [B+'notice/HousingModels.java', B+'notice/HousingModels.java', B+'preparation/PreparationService.java'])
register('schedule', '일정 · Schedule', '공식 일정의 ID·제목·날짜·근거에 개인의 recorded 값을 추가한다. 프론트의 schedules 모듈은 제목의 일정 종류·대표 날짜·시각을 공통 해석하며, 서버 알림은 NoticeSchedules가 마감 중복을 정리한다. 날짜 경과와 실제 신청 완료 기록은 별개다.', [B+'notice/HousingModels.java', B+'preparation/PreparationService.java', F+'features/preparation/ApplicationRecordDialog.tsx', F+'features/housing/schedules.ts', B+'notice/NoticeSchedules.java'])
register('evidence', '출처 값 객체 · Evidence', 'label, url, checkedAt으로 구성된다. 공고·질문·서류·일정 등에 각각 포함되는 값이며 공유 Evidence 테이블이나 외래키는 없다.', [B+'notice/HousingModels.java', F+'shared/ui/ui.tsx'])
register('dbuser', 'app_user', '인증 사용자 테이블. preparation.user_id와 saved_notice.user_id가 소유자를 참조한다. 그림에는 인증·발송 관련 보조 테이블을 생략했다.', [M+'V2__create_accounts.sql', M+'V3__create_housing.sql', M+'V6__member_features.sql'])
register('dbnotice', 'notice', 'NoticeEntity가 매핑하는 물리 테이블. 기본 컬럼 외에 content JSONB를 저장한다. basic_review, guide_review, change_review가 노출용 상태를 결정한다.', [B+'notice/NoticeEntity.java', M+'V3__create_housing.sql'])
register('dbprep', 'preparation', '사용자와 공고의 외래키를 가진 테이블. UNIQUE(user_id, notice_id)이므로 보관 여부와 관계없이 같은 사용자·공고 조합에 준비는 최대 한 개다. snapshot JSONB에 Preparation과 서버용 규칙을 함께 저장한다.', [M+'V3__create_housing.sql', M+'V6__member_features.sql', B+'notice/HousingModels.java'])
register('bookmark', 'saved_notice', '사용자와 공고의 다대다 관계를 연결하는 관심 공고 테이블. 관심 저장과 준비 시작은 별개의 행동이다.', [M+'V6__member_features.sql', B+'member/SavedNoticeService.java'])
register('history', 'preparation_history', '새 안내로 갱신하기 직전의 준비 스냅샷을 보존하는 테이블. 사용자가 여정을 보관하는 보관함과는 다르다.', [M+'V5__archive_preparation_guidance_refresh.sql', B+'preparation/PreparationService.java'])
register('reference', 'experience_reference', '준비 건을 향하는 두 외래키 target_id / source_id로 경험 메모의 참조 관계를 나타낸다. 같은 사용자의 진행 건이 보관 건을 참조한다. 원본을 복원하면 해당 참조는 제거된다.', [M+'V6__member_features.sql', B+'member/ExperienceService.java', B+'member/ExperienceService.java'])
register('versions', 'published_notice_version', '공개 사실의 버전별 JSON을 저장한다. 공고의 전체 원본이나 개인 준비 기록의 복제 테이블은 아니다. 기본 정보 PASS인 변경을 DB 트리거가 기록한다.', [M+'V6__member_features.sql', M+'V8__published_guidance_detail.sql', B+'notice/NoticeVersions.java'])
register('ui', '화면 · ConditionsPage', '답 선택 → 로컬 답변 유지 → 즉시 저장. 저장 성공 때만 다음 질문으로 이동하며, 마지막 질문은 /preparations/:id/conditions/result의 독립 결과 화면으로 이동한다. 내 준비 조건 탭은 /conditions로 구분한다. 자동 저장에는 중복 제출 잠금을 사용한다.', [F+'pages/ConditionsPage.tsx', F+'features/preparation/usePreparation.ts', F+'features/preparation/preparationTabRoutes.ts', F+'app/App.tsx'])
register('transport', 'API 전송', 'api / memberApi가 호출 계약을 제공하고 transport가 JSON·인증·CSRF·실패 처리를 담당한다. 시연 모드에서는 네트워크 대신 demoRequest로 분기한다.', [F+'features/housing/api.ts', F+'features/member/memberApi.ts', F+'shared/api/transport.ts'])
register('service', '서버 준비 서비스', 'JWT 소유자·행 잠금·revision·공고 안내 상태를 확인하고, 답변 검증·기록 갱신·스냅샷 저장을 트랜잭션으로 조율한다. 서류 해당 여부와 준비도·다음 행동은 순수 PreparationRules가 계산하며, 서비스가 반환된 평가를 기록에 적용한다.', [B+'preparation/PreparationController.java', B+'preparation/PreparationService.java', B+'preparation/PreparationService.java', B+'preparation/PreparationRules.java', B+'preparation/PreparationService.java'])
register('active', '보관되지 않은 준비', 'archived_at IS NULL인 준비. 진행률이 100이어도 자동 보관하지 않는다. 홈의 현재 준비 선택값은 별도 브라우저 설정이며 서버의 ACTIVE enum이 아니다.', [B+'preparation/PreparationService.java', F+'features/preparation/useActivePreparation.ts'])
register('archived', '보관한 준비', '같은 preparation 행에 archived_at와 takeaway를 기록한다. 질문 답변·서류·일정 스냅샷은 유지한다. 일반 준비 목록과 편집 대상에서 제외되며 사용자가 복원할 수 있다.', [B+'member/ExperienceService.java', B+'preparation/PreparationService.java'])
register('guide', '준비 안내 상태', 'CHANGE_REVIEW, AVAILABLE, UNSUPPORTED, REVIEWING은 검수 필드로부터 계산되는 노출용 상태다. 접수 OPEN/CLOSED와는 다른 축이다. 기존 준비의 기준 버전이 달라도 CHANGE_REVIEW로 표시한다.', [B+'notice/NoticeService.java', B+'preparation/PreparationService.java'])
register('calculation', '준비도 · 다음 행동 계산', 'PreparationRules.assess가 저장·네트워크 접근과 입력 변경 없이 질문 30점·서류 50점·일정 기록 20점의 비율로 계산한다. UNKNOWN/추가 확인 답변은 질문 점수에 포함하지 않으며, 해당 없는 서류는 분모에서 제외한다. 남은 작업이 있으면 반올림 결과가 100이어도 최대 99로 제한한다. houseStage와 nextAction도 여기서 파생된다. 시연 어댑터의 대응 정책은 별도 실행 환경에 있으며 같은 독립 기대값 계약 사례로 대조한다. NextAction 컴포넌트는 서버 결과를 표시한다. 이는 자격 충족률이 아니다.', [B+'preparation/PreparationRules.java', F+'features/housing/demoPreparationRules.ts', F+'features/preparation/NextAction.tsx', 'data/contracts/preparation-rules.json'])
register('docstate', '서류 상태의 두 축', 'necessity는 답변과 PreparationRules.necessity로 계산한다. state는 편집 가능한 서류에서 사용자가 다섯 값 중 선택할 수 있다. 미확인→준비중→완료 순서를 강제하지 않는다.', [B+'preparation/PreparationRules.java', B+'preparation/PreparationService.java', B+'preparation/PreparationService.java', F+'features/preparation/DocumentEditor.tsx'])
register('app', '프론트엔드 구성', 'React Router가 화면을 연결한다. React Query가 사용자별 조회·변경 캐시를 관리한다. Pages는 화면 조합, features는 공고·준비·회원 기능, shared는 UI·API 전송 등 공통 기능을 제공한다. 이 구분은 주요 코드의 요약이며 엄격한 전 계층 의존성 검증은 아니다.', [F+'app/App.tsx', F+'app/session.tsx', F+'features/preparation/usePreparation.ts'])
register('server', '백엔드 구성', 'Spring 컨트롤러가 요청을 받고 서비스가 업무 규칙과 소유권을 처리한다. 공고는 NoticeEntity / Repository / Querydsl을 사용하며, 준비·회원 기능의 주요 저장은 JdbcTemplate을 사용한다.', [B+'notice/NoticeRepository.java', B+'notice/NoticeService.java', B+'preparation/PreparationService.java', B+'member/MemberController.java'])
register('external', '외부 기관과 발송 경로', 'LH 수집과 공식 신청 사이트, 메일·브라우저 알림 경로는 별개다. 준비 기록 변경은 공식 신청을 대신 제출하지 않는다. 외부 자격 증명·발송 설정의 실제 연결 여부는 이 정적 모델에서 검증하지 않았다.', [B+'collection/LhNoticeClient.java', B+'member/ReminderDelivery.java', B+'member/BrowserPush.java', F+'features/preparation/PreparationOverview.tsx'])
register('publicschedules', '현재 공개 일정 · Notice.schedules', '기본 정보 검수를 통과한 공고의 공식 일정이다. 준비 안내가 검수 중이어도 읽을 수 있으며 개인 recorded 값은 없다. currentPreparationSchedules는 현재 공고를 우선하고, 공고 응답이 없을 때만 AVAILABLE인 개인 준비의 일정을 읽기 대체값으로 사용한다.', [ref(F+'features/housing/schedules.ts', 'export function currentPreparationSchedules('), ref(B+'notice/NoticeService.java', 'public Notice view('), ref(F+'features/housing/types.ts', "schedules?: Omit<Schedule, 'recorded'>[]")])
register('schedulepolicy', '일정 해석 · schedules.ts', '비정형 공고 제목을 게시·접수 시작·접수 마감·발표·서류 마감·신청 기록 등으로 분류하고 한국 날짜와 시각을 해석한다. 대표 발표는 서류 제출 대상자 발표를 우선한다. 동일한 기본/세부 마감은 합치지만 다른 지역·시각·종류는 보존한다.', [ref(F+'features/housing/schedules.ts', 'export function scheduleKind('), ref(F+'features/housing/schedules.ts', 'export function selectSchedule<'), ref(F+'features/preparation/calendar.ts', 'export function calendarItems('), B+'notice/NoticeSchedules.java'])
register('timelinerecord', '현재 일정에 연결한 기록 표시', 'buildScheduleTimeline은 현재 공식 일정에 개인 기록을 읽기 전용으로 결합한다. 공고와 준비의 version이 같고 일정 ID·제목·날짜까지 같은 경우에만 recorded를 표시한다. 불일치는 과거 기록 삭제가 아니며, 공식 일정을 읽었다고 새 준비 기준으로 저장하지 않는다.', [ref(F+'features/preparation/scheduleTimelineModel.ts', 'export function buildScheduleTimeline('), ref(B+'preparation/PreparationService.java', 'public Preparation refresh(')])
register('routes', '조건 화면의 명시적 주소', '공통 접두사 /preparations/:preparationId 아래에서 /conditions는 내 준비 조건 탭, /conditions/edit는 질문, /conditions/result는 독립 결과다. 홈 질문 응답은 조건 탭으로, 질문의 마지막 저장 성공은 독립 결과로 이동한다. location.state는 화면 종류를 바꾸지 않고 복귀 위치만 보존한다.', [ref(F+'app/App.tsx', 'path="/preparations/:preparationId/conditions"'), ref(F+'app/App.tsx', 'path="/preparations/:preparationId/conditions/result"'), ref(F+'features/preparation/preparationTabRoutes.ts', 'export function getPreparationTab('), F+'pages/ConditionsPage.tsx', F+'shared/ui/HousingUI.tsx'])
register('recorddialog', '신청 여부 기록 · ApplicationRecordDialog', '여정의 공식 신청은 개요의 ?application=record로 같은 팝업을 연다. 팝업은 AVAILABLE인 개인 준비 스냅샷에서 기록 대상을 고르고, 사용자가 응답했을 때 preparation ID·schedule ID·revision·recorded를 서버에 보낸다. 닫기 시 query를 제거하며 팝업 열기나 날짜 경과만으로 신청 완료를 기록하지 않는다.', [ref(F+'features/preparation/ApplicationRecordDialog.tsx', 'export function ApplicationRecordDialog('), ref(F+'features/preparation/PreparationOverview.tsx', "requested={params.get('application') === 'record'}"), ref(F+'features/preparation/journey.ts', 'application=record'), ref(B+'preparation/PreparationService.java', 'public Preparation schedule(')])
register('archivecommand', '보관 명령과 메모 초안', 'ArchivePage는 notes[preparationId]로 메모 초안을 보유한다. 보관 명령은 제출 당시 id·revision·note를 포함하고, 복원은 id, 메모 연결은 sourceId·targetId를 보낸다. 보관 성공은 해당 초안만 비우며 복원·연결은 다른 초안을 지우지 않는다. 새로고침 시 미저장 초안을 복원하는 영구 저장 기능은 아니다.', [ref(F+'pages/ArchivePage.tsx', 'type ArchiveCommand ='), ref(F+'pages/ArchivePage.tsx', "[notes, setNotes]"), ref(B+'member/ExperienceService.java', 'public void archive('), ref(B+'member/ExperienceService.java', 'public void restore('), ref(B+'member/ExperienceService.java', 'public void attach(')])
register('demopolicy', '정적 시연 · demoRequest + demoPreparationRules', '명시적 시연 모드만 브라우저 메모리로 실행한다. 서버 체험 계정의 실제 PostgreSQL 저장과 다르다. Java와 TypeScript는 각 실행 환경의 준비 계산을 구현하고 preparation-rules.json의 독립 입력·기대값 사례를 함께 검증한다. 공유 사례가 모든 업무 상태의 동치를 자동 증명하지는 않는다.', [F+'features/housing/demo.ts', ref(F+'features/housing/demoPreparationRules.ts', 'export function assessDemoPreparation('), 'data/contracts/preparation-rules.json', B+'preparation/PreparationRules.java'])

# Core responsibilities are linked to their actual declarations rather than old line numbers.
nodes['calculation']['refs'] = [ref(B+'preparation/PreparationRules.java', 'public static Assessment assess('), ref(B+'preparation/PreparationRules.java', 'public static String necessity('), ref(F+'features/preparation/NextAction.tsx', 'export function NextAction('), 'data/contracts/preparation-rules.json']
nodes['service']['refs'] = [ref(B+'preparation/PreparationService.java', 'public Preparation answers('), ref(B+'preparation/PreparationService.java', 'private Snapshot editable('), ref(B+'preparation/PreparationService.java', 'private Preparation save('), ref(B+'preparation/PreparationService.java', 'private Preparation applyAssessment(')]
nodes['prep']['refs'] = [ref(B+'notice/HousingModels.java', 'public static class Preparation {'), ref(B+'notice/HousingModels.java', 'public record Snapshot('), ref(F+'features/housing/types.ts', 'export interface Preparation {')]

def text(x,y,s,cls='label',anchor='start'):
    return f'<text x="{x}" y="{y}" class="{cls}" text-anchor="{anchor}">{esc(s)}</text>'

def box(key,x,y,w,title,kind,lines,tone='normal'):
    h=76+len(lines)*24
    body=f'<g class="node {tone}" data-node="{key}" role="button" tabindex="0" aria-label="{esc(title)} 설명"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8"/>'
    body+=text(x+16,y+23,kind,'stereo')+text(x+16,y+49,title,'node-title')
    body+=f'<path d="M{x},{y+62}H{x+w}" class="separator"/>'
    for i,line in enumerate(lines): body+=text(x+16,y+87+i*24,line,'field')
    return body+'</g>'

def edge(path,label='',x=0,y=0,dashed=False,composition=False):
    s=f'<path d="{path}" class="edge {"dashed" if dashed else ""}" marker-end="url(#arrow)"'+(' marker-start="url(#diamond)"' if composition else '')+'/>'
    return s+(text(x,y,label,'edge-label') if label else '')

def canvas(body,h=730):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 {h}" role="group" aria-label="코드 모델 다이어그램"><style>.node rect{{fill:#fff;stroke:#b8c3ce;stroke-width:1.4}}.node.accent rect{{fill:#fff6ef;stroke:#bf5438;stroke-width:2}}.node:hover rect,.node:focus rect{{stroke:#bf5438;stroke-width:3}}.node{{cursor:pointer}}.node-title{{font:700 19px sans-serif;fill:#202b39}}.stereo{{font:12px monospace;fill:#64748b}}.field{{font:14px monospace;fill:#334155}}.label{{font:15px sans-serif;fill:#334155}}.edge-label{{font:13px sans-serif;fill:#475569;paint-order:stroke;stroke:#fafbfd;stroke-width:5px;stroke-linejoin:round}}.small{{font:13px sans-serif;fill:#64748b}}.title{{font:700 18px sans-serif;fill:#202b39}}.edge{{fill:none;stroke:#718096;stroke-width:1.6}}.dashed{{stroke-dasharray:6 5}}.separator{{stroke:#d8dee6;stroke-width:1}}.zone{{fill:#f2f5f8;stroke:#dce3eb;stroke-width:1}}.lifeline{{stroke:#c7d0da;stroke-dasharray:5 5}}.note{{fill:#fff6e8;stroke:#e8d3ae}}</style><defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M1 1L9 5L1 9" fill="none" stroke="#718096" stroke-width="1.5"/></marker><marker id="diamond" viewBox="0 0 12 10" refX="0" refY="5" markerWidth="11" markerHeight="9" orient="auto"><path d="M0 5L6 0L12 5L6 10Z" fill="#718096"/></marker></defs>{body}</svg>'

diagrams=[]
def add(id,title,tag,description,svg,notes,refs):
    diagrams.append(dict(id=id,title=title,tag=tag,description=description,svg=svg,notes=notes,refs=refs))

# UML: owned snapshot parts are composition; conceptual owner links are associations.
s=''
s+=edge('M300 126H375V245H450','1 소유자 → 0..* 준비',304,109)
s+=edge('M310 410H375V355H450','1 공고 → 0..* 준비',312,395)
for yy,ty,label in [(260,110,'0..* 질문 사본'),(310,285,'0..* 답변 항목'),(340,470,'0..* 개인 서류'),(373,655,'0..* 개인 일정')]:
    s+=edge(f'M740 {yy}H805V{ty}H875',label,750,ty-14,composition=True)
s+=edge('M175 468V584','1 근거',190,552,dashed=True)
s+=box('user',40,55,260,'User · 사용자','«response / owner»',['id, role, trial','name, email'])
s+=box('notice',40,320,270,'Notice · 공고','«public DTO / Content JSON»',['id, title, agency, region','version, guideState','questions / templates'])
s+=box('prep',450,195,290,'Preparation · 준비','«personal snapshot»',['noticeId, version, revision','questions[], answers{}','documents[], schedules[]','progress, houseStage','warnings[], nextAction'],'accent')
s+=box('question',875,35,285,'Question · 질문','«snapshot part»',['id, title, reason','options[], evidence'])
s+=box('answer',875,225,285,'answers · 답변','«Map, not an entity»',['questionId → optionValue'])
s+=box('document',875,390,285,'Document · 서류','«snapshot part»',['necessity ≠ state','note, issueUrl, evidence','공고 템플릿에서 생성'])
s+=box('schedule',875,595,285,'Schedule · 일정','«snapshot part»',['date, recorded, evidence'])
s+=box('evidence',40,584,270,'Evidence · 근거','«value object, no table»',['label, url, checkedAt'])
s+=text(450,552,'◆ 포함 관계   → 연결 / 참조   ⇢ 값 포함','small')
s+=text(450,580,'한 준비 안에서 질문당 답변은 0..1개.','label')
s+=text(450,609,'질문·서류·일정에도 각각 Evidence가 포함된다.','small')
s+=text(450,637,'답변 Map은 별도 테이블이나 Java 클래스가 아니다.','small')
add('domain','핵심 개념','UML 클래스 관계','공고를 기준으로 만들어지는 사용자별 준비 기록과 그 내부 구성 요소입니다. DB 테이블이 아닌 논리적 모델입니다.',canvas(s,755),['준비는 공고를 실시간으로 공유하는 객체가 아니라 기준 버전의 내용을 복사한 스냅샷입니다.','progress는 준비 작업의 계산 결과이며 자격 판정이나 선정 가능성이 아닙니다.','Evidence는 링크와 확인일을 가진 값 객체입니다. 제출서류 원본 파일은 이 모델에 없습니다.'],['prep','notice','question','answer','document','schedule','evidence','calculation'])

s=''
s+=edge('M290 120H425','1 → 0..*',320,104)
s+=edge('M900 120H715','1 → 0..*',773,104)
s+=edge('M160 169V365','1 → 0..*',177,280)
s+=edge('M1035 193V310H290V410','notice 1 → saved 0..*',600,295)
s+=edge('M570 217V365','1 → 0..*',588,330)
s+=edge('M1035 193V365','1 → 0..*',1049,335)
s+=edge('M425 215H350V638H425','target / source → preparation',40,614)
s+=box('dbuser',30,45,260,'app_user','«table»',['PK id','name, email, role'])
s+=box('dbprep',425,45,290,'preparation','«table»',['PK id; FK user_id, notice_id','notice_version, revision','snapshot: JSONB','archived_at?, takeaway'],'accent')
s+=box('dbnotice',900,45,270,'notice','«table / JPA Entity»',['PK id; version, revision','basic_review, guide_review','content: JSONB'])
s+=box('bookmark',30,365,260,'saved_notice','«join table»',['PK/FK user_id, notice_id','saved_version'])
s+=box('history',425,365,290,'preparation_history','«guidance history»',['FK preparation_id','notice_version, snapshot','UNIQUE(preparation_id,', '       notice_version)'])
s+=box('versions',900,365,270,'published_notice_version','«public facts history»',['PK notice_id, version,','    revision','facts: JSONB'])
s+=box('reference',425,580,340,'experience_reference','«self reference join»',['FK target_id → preparation','FK source_id → preparation'])
s+=text(35,700,'多重度は親 1 に対して子 0..*。','small').replace('多重度は親 1 に対して子 0..*。','화살표는 참조 관계 요약 · 숫자는 관계 다중성')
s+=text(810,610,'UNIQUE(user_id, notice_id)','title')
s+=text(810,641,'같은 사용자 + 공고의 준비는 최대 1개.','label')
s+=text(810,669,'보관함도 별도 테이블이 아닌 같은 행.','label')
add('storage','실제 저장 구조','ER / 관계형 + JSON','주요 물리 테이블만 나타낸 축약 ER입니다. 표의 PK·FK와 연결선의 다중성을 함께 읽습니다.',canvas(s,760),['preparation.snapshot = Snapshot { preparation, rules, ruleValues }. 질문·답변·개인 서류·일정은 이 JSON 안에 저장됩니다.','preparation_history는 안내 갱신 이력, archived_at는 사용자 보관함입니다. 두 개념은 다릅니다.','인증·검수 이력(review_audit)·알림·수집 등 보조 테이블은 가독성을 위해 생략했습니다.'],['dbprep','dbnotice','dbuser','bookmark','history','reference','versions'])

s=''
actors=[('user',10,'사용자'),('ui',205,'질문 화면'),('transport',400,'API 전송'),('service',595,'Service'),('calculation',790,'Rules'),('dbprep',1000,'DB')]
for key,x,title in actors:
    s+=f'<g class="node" data-node="{key}" role="button" tabindex="0"><rect x="{x}" y="25" width="155" height="48" rx="6"/>{text(x+77,55,title,"node-title","middle")}</g>'
    s+=f'<line class="lifeline" x1="{x+77}" x2="{x+77}" y1="73" y2="810"/>'
def msg(a,b,y,label,dash=False):
    return edge(f'M{a} {y}H{b}',label,min(a,b)+14,y-11,dash)
s+=msg(87,282,120,'1. 답 선택')
s+=text(300,151,'로컬 선택 유지 · 중복 제출 잠금','small')
s+=msg(282,477,195,'2. 답 + revision')
s+=msg(477,672,250,'3. PUT answers')
s+=text(487,275,'인증 + CSRF','small')
s+=msg(672,1077,320,'4. 소유자 조회 · 준비/공고 행 잠금')
s+=text(535,352,'Service: revision · 안내 상태 · 질문/선택값 검증','small')
s+=msg(672,867,400,'5. necessity / assess')
s+=msg(867,672,450,'계산 결과 반환',True)
s+=text(886,411,'DB 접근 없음','small')
s+=text(886,439,'입력 변경 없음','small')
s+=text(620,486,'Service: 결과 적용 · revision++','small')
s+=msg(672,1077,530,'6. snapshot 저장 · 트랜잭션 완료')
s+=msg(1077,672,570,'저장 완료',True)
s+=f'<rect class="zone" x="200" y="600" width="970" height="85" rx="8"/>'
s+=text(215,625,'성공','title')
s+=msg(672,282,662,'7. 응답 → 캐시 반영 → 다음 질문 / 결과',True)
s+=f'<rect class="note" x="200" y="710" width="970" height="96" rx="8"/>'
s+=text(215,737,'실패 분기','title')
s+=text(235,768,'답변 유지 · 오류 표시 · 이동하지 않음 · “다음”으로 재시도','label')
s+=text(235,793,'404: 소유자/보관 여부 등 조회 불가    409: revision 충돌 또는 안내 편집 불가','small')
add('sequence','답변 저장 흐름','UML 시퀀스','Service는 검증·저장, PreparationRules는 순수 계산을 맡습니다. 둘은 같은 서버 프로세스의 호출이며 별도 계산 API가 아닙니다.',canvas(s,845),['화면의 “다음”과 답 재선택도 같은 저장 경로를 사용합니다. 저장 실패 시 선택값은 유지됩니다.','necessity는 답변 변경에 따른 서류 필요 여부를 계산합니다. assess는 준비도·집 단계·경고·다음 행동을 Assessment로 반환하고 Service가 이를 기록에 적용합니다.','공고 기준 version과 준비 수정 revision은 다릅니다. 409에 대한 자동 병합은 없습니다. 여기의 revision++는 준비 내용 저장 경로이며 보관·복원에 일반화하지 않습니다.'],['ui','transport','service','calculation','dbprep'])

s=f'<rect class="zone" x="20" y="20" width="1160" height="250" rx="12"/>'+text(40,53,'A. 준비 기록의 생명주기','title')
s+=edge('M120 150H245','준비 시작',130,130)
s+='<circle cx="105" cy="150" r="10" fill="#334155"/>'
s+=box('active',245,88,300,'보관되지 않음','archived_at IS NULL',['일반 목록 · 조건/서류 편집'])
s+=box('archived',790,88,300,'보관됨','archived_at IS NOT NULL',['스냅샷 유지 · 편집 제외'])
s+=edge('M545 128H790','보관: 소유자 + revision 확인',553,111)
s+=edge('M790 178H545','복원: archived_at = NULL',564,206)
s+=text(40,245,'보관은 archived_at·takeaway만 변경하며 revision을 올리지 않는다. 준비도 100% 조건은 없다.','small')
s+=f'<rect class="zone" x="20" y="295" width="555" height="415" rx="12"/>'+text(40,330,'B. 안내 상태의 결정 규칙','title')
for yy,rule,state in [(370,'changeReview = true','CHANGE_REVIEW'),(449,'그 외 + guideReview = PASS','AVAILABLE'),(528,'그 외 + guideReview = UNSUPPORTED','UNSUPPORTED'),(607,'나머지','REVIEWING')]:
    s+=f'<g class="node" data-node="guide" role="button" tabindex="0"><rect x="40" y="{yy-20}" width="515" height="65" rx="6"/>{text(54,yy,rule,"small")}{text(54,yy+28,state,"node-title")}</g>'
s+=text(40,691,'위에서부터 우선 적용 · 접수 상태와 별개','small')
s+=f'<rect class="zone" x="600" y="295" width="580" height="415" rx="12"/>'+text(620,330,'C. 서류의 독립적인 두 상태 축','title')
s+=box('docstate',620,355,540,'necessity · 필요 여부','«calculated»',['REQUIRED / CONDITIONAL','UNKNOWN / NOT_APPLICABLE'])
s+=box('docstate',620,500,540,'state · 준비 상태','«user record»',['UNCHECKED / NEEDED / IN_PROGRESS','READY / RECHECK'])
s+=text(620,660,'다섯 상태 사이에 순서 강제는 없음.','label')
s+=text(620,687,'규칙 변경 시 READY → RECHECK 자동 전환 가능.','small')
add('states','상태와 경계','상태도 + 결정표','화면에서 비슷하게 보이는 상태를 독립적인 축으로 나눴습니다. 모든 상태를 하나의 선형 단계로 연결하면 실제 코드와 달라집니다.',canvas(s,735),['기본 정보 PASS만 일반 공개됩니다. 신규 준비는 안내 AVAILABLE이고 접수 상태가 CLOSED가 아닐 때 시작할 수 있습니다.','기존 준비와 최신 공고의 version이 다르면 CHANGE_REVIEW로 표시합니다. 사용자가 안내 갱신을 요청해야 기준이 교체됩니다.','서류 necessity가 UNKNOWN 또는 NOT_APPLICABLE이면 상태 수정이 막힙니다. 필요 여부 변경은 기존 READY를 RECHECK로 바꿀 수 있습니다.','복원은 소유자를 확인하고 archived_at를 비웁니다. 복원한 준비를 source로 삼았던 experience_reference는 삭제하지만 답변·신청 기록 스냅샷은 유지합니다.'],['active','archived','guide','docstate','archivecommand'])

s=''
s+=box('app',25,40,320,'React 화면·기능','«app / pages / features»',['Router · React Query','NextAction: 서버 결과 표시','일정 해석: schedules.ts'],'accent')
s+=box('transport',445,40,310,'API 경계','«api / memberApi / transport»',['인증·CSRF·실패 처리','사용자별 캐시 갱신'])
s+=box('server',865,40,310,'Controller','«Spring Web / Security»',['JWT subject → 소유자','요청 계약 · Service 호출'])
s+=edge('M345 125H445','요청',376,110)
s+=edge('M755 125H865','HTTP',783,110)
s+=edge('M1020 164V280','위임',1040,235)
s+=box('demopolicy',25,280,320,'정적 시연 경로','«브라우저 메모리»',['demoRequest','demoPreparationRules','명시한 시연 모드에서만 실행'])
s+=edge('M600 164V230H185V280','시연 모드 분기',280,216,dashed=True)
s+=box('calculation',445,280,310,'PreparationRules','«pure policy»',['necessity → 서류 필요 여부','assess → Assessment','저장·네트워크·입력 변경 없음'],'accent')
s+=box('service',865,280,310,'PreparationService','«transaction orchestration»',['소유권 · 잠금 · revision','규칙 호출 · 결과 적용','기준 갱신 · snapshot 저장'])
s+=edge('M865 374H755','계산 호출',775,356)
s+=edge('M755 417H865','계산 결과',775,445,dashed=True)
s+=box('dbprep',865,550,310,'PostgreSQL','«persistence»',['준비·회원: JdbcTemplate','공고: JPA / Querydsl','관계형 컬럼 + JSONB'])
s+=edge('M1020 428V550','조회 / 저장',1040,510)
s+=box('demopolicy',25,550,730,'같은 입력·독립 기대값으로 대조','«test contract · 런타임 호출 아님»',['data/contracts/preparation-rules.json','PreparationRulesTest.java  ↔  demoPreparationRules.test.ts'])
s+=edge('M185 428V550','TS 계약 사례',202,510,dashed=True)
s+=edge('M600 428V550','Java 계약 사례',617,510,dashed=True)
s+=box('external',25,775,1150,'다른 업무 경계','«독립된 기능 서비스»',['NoticeService: 공고 검수·공개  |  NoticeSchedules → AlertService: 공개 일정 알림','ExperienceService: 보관·복원·메모 참조  |  수집·메일·Web Push: 설정된 외부 경로'])
add('architecture','구성요소','컴포넌트 의존 그래프','검증·저장 서비스와 순수 계산 정책을 분리한 현재 구조입니다. 런타임 요청과 테스트 대조는 서로 다른 연결로 나타냈습니다.',canvas(s,940),['PreparationRules는 입력을 변경하지 않고 계산 결과를 반환합니다. Service가 결과를 개인 기록에 적용하고 트랜잭션으로 저장합니다.','정적 시연은 브라우저 메모리 저장입니다. 가입 없이 체험하는 서버 임시 계정은 실제 API·PostgreSQL 경로를 사용하므로 정적 시연과 다릅니다.','Java와 TypeScript의 정책은 실행 환경이 달라 각각 구현합니다. 공유 계약 사례로 대조하지만 Java 코드를 브라우저가 호출하는 구조는 아닙니다.','공식 신청은 외부 기관에서 수행합니다. 이 사이트의 recorded는 사용자가 남긴 신청 여부 기록이며 실제 제출 성공을 조회한 값이 아닙니다.','외부 수집·메일·푸시의 코드 경로와 운영 자격 증명·실제 발송 성공은 구분해야 합니다. 이 그림은 코드 구조 모델입니다.'],['app','transport','server','service','calculation','demopolicy','dbprep','external'])

s=f'<rect class="zone" x="20" y="20" width="1160" height="300" rx="12"/>'+text(40,55,'A. 공개 일정 읽기와 개인 완료 표시','title')
s+=box('publicschedules',40,85,300,'Notice.schedules','«current public facts»',['ScheduleTemplate[]','개인 recorded 없음'])
s+=box('schedulepolicy',455,85,300,'일정 해석','«schedules.ts»',['종류 · 대표 날짜 · 시각','상세 · D-day · 캘린더 소비'])
s+=box('timelinerecord',870,85,290,'타임라인 표시','«read-only view model»',['현재 공식 일정','일치하는 기록만 연결'])
s+=edge('M340 145H455','읽기',382,132)
s+=edge('M755 145H870','표시',793,132)
s+=text(40,258,'Preparation.schedules.recorded','title')
s+=edge('M390 252H1015V209','동일 version + 일정 ID·제목·날짜',535,241,dashed=True)
s+=text(40,297,'현재 일정으로 읽는 것과 준비 기준을 갱신하는 것은 다르다. 원래 개인 스냅샷은 보존된다.','small')
s+=f'<rect class="zone" x="20" y="345" width="1160" height="410" rx="12"/>'+text(40,380,'B. 조건 화면과 신청 기록의 진입 경로','title')
s+=text(40,407,'아래 주소는 /preparations/:preparationId 뒤에 붙는 경로','small')
s+=box('routes',40,435,300,'홈 · 질문 응답','«navigation»',['/conditions','내 준비의 조건 탭'])
s+=box('routes',455,435,300,'질문 화면','«questions»',['/conditions/edit','선택 답변 저장'])
s+=box('routes',870,435,290,'독립 결과','«result»',['/conditions/result','마지막 저장 성공 후 이동'])
s+=edge('M755 495H870','성공',790,480)
s+=box('recorddialog',40,610,300,'여정 · 공식 신청','«navigation»',['개요 ?application=record'])
s+=box('recorddialog',455,610,300,'신청 기록 팝업','«one dialog»',['직접 버튼과 같은 기능'])
s+=box('schedule',870,610,290,'개인 일정 저장','«API command»',['id · revision · recorded'])
s+=edge('M340 670H455','열기',380,657)
s+=edge('M755 670H870','응답할 때',771,657)
s+=f'<rect class="zone" x="20" y="780" width="1160" height="240" rx="12"/>'+text(40,815,'C. 보관은 제출한 대상에만 적용','title')
s+=box('archivecommand',40,845,300,'대상별 메모 초안','«ArchivePage local state»',['notes[preparationId]'])
s+=box('archivecommand',455,845,300,'ArchiveCommand','«submitted values»',['archive: id·revision·note'])
s+=box('archived',870,845,290,'보관 / 복원 / 연결','«ExperienceService»',['소유권 · 대상 상태 검증'])
s+=edge('M340 905H455','제출 값',369,892)
s+=edge('M755 905H870','명령',791,892)
s+=text(40,995,'보관 성공 시 해당 초안만 정리. 복원·메모 연결은 다른 초안을 지우지 않는다.','small')
add('boundaries','일정·탐색 경계','데이터 흐름 + 탐색 그래프','공개 사실과 개인 기록, 화면 진입과 저장 명령을 구분합니다. 리팩토링 뒤 실제 호출자가 사용하는 경로입니다.',canvas(s,1040),['공개 일정은 준비 안내 AVAILABLE 여부로 숨기지 않습니다. 공고 자체가 아직 없을 때만 AVAILABLE인 준비 스냅샷을 읽기 대체값으로 사용합니다.','타임라인은 같은 기준의 기록만 표시합니다. 신청 기록 팝업은 현재 공개 일정이 아니라 편집 가능한 개인 준비 스냅샷을 저장 대상으로 삼습니다.','조건 탭과 독립 결과는 주소로 구분됩니다. 팝업을 닫으면 application query를 replace로 제거하고, 다시 열 때 이전 오류를 초기화합니다.','보관은 id·revision·note, 복원은 id, 메모 연결은 sourceId·targetId를 보냅니다. 미저장 초안은 해당 화면의 메모리 상태이며 새로고침 후 영구 보존하는 기능이 아닙니다.'],['publicschedules','schedulepolicy','timelinerecord','routes','recorddialog','archivecommand','archived'])

HEAD = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
DATA=json.dumps(dict(diagrams=diagrams,nodes=nodes),ensure_ascii=False).replace('<','\\u003c')
TEMPLATE='''<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>집으로 · 코드 개념 지도</title>
<style>
:root{font-family:Inter,'Segoe UI','Malgun Gothic',sans-serif;color:#243142;background:#f6f7f9;--brand:#b64a32}*{box-sizing:border-box}body{margin:0}button{font:inherit;cursor:pointer}a{color:inherit}button:focus-visible,a:focus-visible{outline:3px solid #bb6c42;outline-offset:3px}header{background:#fff;border-bottom:1px solid #e1e6ed;padding:30px max(24px,calc((100vw - 1280px)/2)) 22px}.eyebrow{font-size:12px;letter-spacing:2px;color:var(--brand);font-weight:700}h1{font-size:32px;letter-spacing:-1px;margin:10px 0}header p{margin:0;color:#5f6d7f;font-size:15px;line-height:1.7}.meta{display:flex;flex-wrap:wrap;gap:8px;margin-top:17px}.meta span{border:1px solid #e0e5ec;background:#fafbfd;padding:5px 9px;border-radius:6px;font:12px monospace}main{max-width:1328px;margin:auto;padding:24px}nav{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:20px}nav button{border:1px solid #d8dfe7;border-radius:9px;background:#fff;padding:12px 16px;color:#556274;font-size:14px}nav button[aria-pressed=true]{background:#273447;border-color:#273447;color:white}nav i{font-style:normal;font-size:11px;opacity:.65;margin-right:8px}.intro{display:flex;justify-content:space-between;align-items:end;gap:16px;margin:18px 0}.intro h2{font-size:24px;margin:5px 0 8px}.intro p{max-width:780px;margin:0;color:#607083;line-height:1.7;font-size:14px}.tag{color:var(--brand);font-size:12px;font-weight:700}.toolbar{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px}.toolbar button{background:white;border:1px solid #dce2e9;border-radius:7px;padding:8px 12px;color:#354456;font-size:13px}.toolbar output{align-self:center;padding:0 10px;font-size:12px;color:#64748b}.board{background:#fafbfd;border:1px solid #dae1e9;border-radius:12px;overflow:auto;max-height:78vh;scrollbar-gutter:stable;box-shadow:0 6px 24px #2b3b5010}.board svg{display:block;width:100%;min-width:800px;height:auto}.under{display:grid;grid-template-columns:minmax(0,1fr) minmax(280px,.8fr);gap:16px;margin-top:16px}.notes,.inspector{background:white;border:1px solid #e0e6ec;border-radius:12px;padding:20px}.under h3{margin:0 0 12px;font-size:16px}.under p,.under li{font-size:14px;line-height:1.8;color:#526174}.under ul{margin:0;padding-left:18px}.under li+li{margin-top:7px}.inspector{border-top:3px solid var(--brand)}.inspector p{margin:0 0 12px}.inspector code,.source-list code{display:block;overflow-wrap:anywhere;font-size:12px;line-height:1.65;color:#526174;background:#f5f7fa;padding:8px;border-radius:5px;margin-top:6px}.hint{font-size:12px;color:#6d7b8c;margin:10px 0 0}details{margin-top:18px;padding:16px;background:#fff;border:1px solid #e0e6ec;border-radius:10px}summary{cursor:pointer;font-size:14px;font-weight:600}.source-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding-top:12px}.source-list strong{font-size:13px}footer{margin-top:24px;border-top:1px solid #dce2e9;padding-top:16px;font-size:12px;line-height:1.8;color:#64748b}.print-only{display:none}[hidden]{display:none!important}@media(max-width:760px){header{padding:22px 18px}h1{font-size:27px}main{padding:16px}.under{grid-template-columns:1fr}nav{gap:6px}nav button{flex:1 0 42%;padding:11px 8px}.intro{display:block}.source-list{grid-template-columns:1fr}.board{max-height:65vh}}@media print{nav,.toolbar,.hint,.inspector,details{display:none!important}body{background:#fff}header{padding:12px}main{padding:0}.board{max-height:none;overflow:visible;box-shadow:none;border:0}.board svg{min-width:0;width:100%!important}.under{display:block}.notes{padding:8px;border:0}.print-only{display:block;break-before:page}footer{font-size:10px}}
</style></head><body>
<header><div class="eyebrow">JIBEURO / CODE ATLAS</div><h1>집으로 코드 개념 지도</h1><p>공고에서 나의 준비 기록까지, 실제 코드에 들어 있는 개념과 책임을 여섯 가지 관점으로 읽습니다.</p><div class="meta"><span>2026.11.06 리팩토링 반영본</span><span>HEAD __HEAD__ + 로컬 변경</span><span>외부 라이브러리 없이 열리는 단일 HTML</span></div></header>
<main><nav aria-label="모델 관점" id="nav"></nav><section aria-labelledby="view-title"><div class="intro"><div><span class="tag" id="tag"></span><h2 id="view-title"></h2><p id="description"></p></div></div><div class="toolbar"><button id="minus" aria-label="다이어그램 축소">−</button><button id="fit">맞춤</button><button id="plus" aria-label="다이어그램 확대">＋</button><output id="zoom">맞춤</output><button id="export">SVG 저장</button><button id="print">전체 인쇄 / PDF</button></div><div class="board" id="board" tabindex="0" aria-label="다이어그램, 작은 화면에서는 가로로 스크롤하세요"></div><p class="hint">상자를 누르면 코드 근거를 확인할 수 있습니다. 작은 화면에서는 그림을 가로로 이동하세요. ◆ 내부 구성 · 실선 연결 · 점선 반환/보조 관계</p><div class="under"><section class="notes"><h3>이 그림에서 구분할 것</h3><ul id="notes"></ul></section><aside class="inspector" aria-live="polite"><h3 id="node-title">상자를 선택해 보세요</h3><p id="node-description">개념의 의미와 실제 코드 위치를 이곳에 표시합니다.</p><div id="node-refs"></div></aside></div><details><summary>이 관점의 코드 근거</summary><div class="source-list" id="sources"></div></details></section><div id="printViews" class="print-only"></div><footer><strong>범위와 표기</strong><br>분석 대상: C:/Users/Administrator/Documents/jibro · feature/local-mobile-screens-20261103<br>현재 작업 트리의 타입·서비스·마이그레이션·화면을 근거로 추상화한 문서입니다. 전체 클래스/테이블을 나열한 자동 역공학 결과는 아닙니다. 줄번호는 코드 수정에 따라 달라질 수 있습니다.<br>기준 파일: frontend/src/features/housing/types.ts · backend/.../notice/HousingModels.java · PreparationService.java · PreparationRules.java · schedules.ts · V3/V5/V6/V8 마이그레이션. 실행 중 데이터·계정 정보·비밀값은 포함하지 않습니다.</footer></main>
<script id="model-data" type="application/json">__DATA__</script><script>
const model=JSON.parse(document.getElementById('model-data').textContent);let index=0,scale=1;const $=id=>document.getElementById(id);const encode=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function inspect(key){const n=model.nodes[key];if(!n)return;$('node-title').textContent=n.title;$('node-description').textContent=n.description;$('node-refs').innerHTML=n.refs.map(r=>'<code>'+encode(r)+'</code>').join('')}
function choose(i){index=i;scale=1;const d=model.diagrams[i];$('tag').textContent=d.tag;$('view-title').textContent=d.title;$('description').textContent=d.description;$('board').innerHTML=d.svg;$('board').scrollTo(0,0);$('zoom').textContent='맞춤';$('notes').innerHTML=d.notes.map(t=>'<li>'+encode(t)+'</li>').join('');$('sources').innerHTML=d.refs.map(k=>'<div><strong>'+encode(model.nodes[k].title)+'</strong>'+model.nodes[k].refs.map(r=>'<code>'+encode(r)+'</code>').join('')+'</div>').join('');document.querySelectorAll('nav button').forEach((b,j)=>b.setAttribute('aria-pressed',i===j));inspect(d.refs[0]);location.hash=d.id;}
model.diagrams.forEach((d,i)=>{let b=document.createElement('button');b.innerHTML='<i>0'+(i+1)+'</i>'+encode(d.title);b.onclick=()=>choose(i);$('nav').append(b)});
$('board').addEventListener('click',e=>{const n=e.target.closest('[data-node]');if(n)inspect(n.dataset.node)});$('board').addEventListener('keydown',e=>{const n=e.target.closest('[data-node]');if(n&&(e.key==='Enter'||e.key===' ')){e.preventDefault();inspect(n.dataset.node)}});
function zoom(delta){scale=Math.max(.6,Math.min(2.5,scale+delta));const svg=$('board').querySelector('svg');svg.style.width=Math.round(Math.max(800,$('board').clientWidth)*scale)+'px';svg.style.minWidth='0';$('zoom').textContent=Math.round(scale*100)+'%'}$('plus').onclick=()=>zoom(.2);$('minus').onclick=()=>zoom(-.2);$('fit').onclick=()=>{scale=1;const s=$('board').querySelector('svg');s.style.width='100%';s.style.minWidth='800px';$('zoom').textContent='맞춤'};
$('export').onclick=()=>{const blob=new Blob([model.diagrams[index].svg],{type:'image/svg+xml;charset=utf-8'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='jibro-'+model.diagrams[index].id+'.svg';a.click();setTimeout(()=>URL.revokeObjectURL(url),2000)};
$('print').onclick=()=>{ $('printViews').innerHTML=model.diagrams.filter((_,i)=>i!==index).map(d=>'<section style="break-before:page"><h2>'+encode(d.title)+'</h2><p>'+encode(d.description)+'</p><div class="board">'+d.svg+'</div><ul>'+d.notes.map(n=>'<li>'+encode(n)+'</li>').join('')+'</ul></section>').join('');window.print()};
const initial=model.diagrams.findIndex(d=>d.id===location.hash.slice(1));choose(initial<0?0:initial);
</script></body></html>'''
OUT.write_text(TEMPLATE.replace('__DATA__',DATA).replace('__HEAD__',esc(HEAD)),encoding='utf-8')
print(f'Generated {OUT} ({OUT.stat().st_size:,} bytes)')
