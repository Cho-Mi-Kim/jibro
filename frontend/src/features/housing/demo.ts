import type {
    Answer,
    Collection,
    Guide,
    Notice,
    Preparation,
    Review,
    User,
} from './types';
import { ApiError } from '../../shared/api/client';
const evidence = {
    label: '시연용 자료 · 실제 모집 공고 아님',
    url: null,
    checkedAt: '2026-10-30',
};
const questions = [
    {
        id: 'residence',
        title: '공고 기준일의 거주 조건을 확인했나요?',
        reason: '실제 공고의 기준일과 주민등록상 거주지를 확인해야 합니다.',
        evidence,
    },
    {
        id: 'house',
        title: '본인에게 적용되는 무주택 기준을 확인했나요?',
        reason: '가구원 범위와 예외는 공고마다 다릅니다.',
        evidence,
    },
];
export const notices: Notice[] = [
    'AVAILABLE',
    'REVIEWING',
    'UNSUPPORTED',
    'CHANGE_REVIEW',
].map((state, index) => ({
    id: String(index + 1),
    title: [
        '[시연] 청년 주거 준비 예시',
        '[시연] 주거비 지원 예시',
        '[시연] 공공임대 안내 예시',
        '[시연] 수정 공고 예시',
    ][index],
    agency: '가상 운영기관',
    region: index % 2 ? '경기' : '서울',
    type: index % 2 ? '주거비 지원' : '공공임대',
    status: index === 2 ? 'CLOSED' : 'OPEN',
    startDate: '2026-10-01',
    publishedDate: '2026-09-30',
    attachments: [],
    endDate: index === 2 ? '2026-10-15' : '2026-12-10',
    summary:
        '화면과 준비 흐름을 확인하기 위한 가상 데이터입니다. 실제 신청에 사용할 수 없습니다.',
    version: 1,
    guideVersion: state === 'AVAILABLE' ? 1 : null,
    guideState: state as Notice['guideState'],
    canStart: state === 'AVAILABLE',
    evidence,
    questions: state === 'AVAILABLE' ? questions : [],
    documents:
        state === 'AVAILABLE' ? ['거주 확인 서류', '조건별 확인 서류'] : [],
}));
let user: User | null = null;
const preparations: Preparation[] = [];
const reviews: Review[] = notices.map((n) => ({
    id: n.id,
    notice: n,
    basic: 'PASS',
    guide: n.canStart ? 'PASS' : 'HOLD',
    reason: '시연 데이터',
    revision: 1,
}));
const collections: Collection[] = [];
const guides: Guide[] = [
    {
        id: 'conditions',
        category: '조건과 용어',
        title: '신청 자격과 선정 순위는 어떻게 다른가요?',
        body: '신청 자격은 신청 대상의 기준이며, 선정 순위는 대상자 사이의 우선순위입니다. 실제 판단은 선택한 공고의 공식 기준을 확인해야 합니다.',
        evidence,
    },
    {
        id: 'documents',
        category: '서류 발급 안내',
        title: '서류를 발급하기 전에 확인할 내용',
        body: '제출 대상, 발급 기준일, 문서 종류와 제출 방법을 공고에서 확인하세요. 모든 공고에 같은 서류나 발급 조건이 적용되는 것은 아닙니다.',
        evidence,
    },
    {
        id: 'process',
        category: '신청 절차',
        title: '공식 사이트를 열면 신청이 완료되나요?',
        body: '공식 기관에서 접수를 마친 뒤 접수 결과를 확인하세요. 집으로의 준비 기록과 실제 기관의 접수는 별개입니다.',
        evidence,
    },
];
function requireUser() {
    if (!user) throw new ApiError(401, '로그인이 필요합니다.');
}
export function calculate(p: Preparation): Preparation {
    const known = p.questions.filter(
        (q) => p.answers[q.id] && p.answers[q.id] !== 'UNKNOWN',
    ).length;
    const docs = p.documents.filter((d) => d.necessity !== 'NOT_APPLICABLE');
    const ready = docs.filter(
        (d) => d.state === 'READY' && d.necessity !== 'UNKNOWN',
    ).length;
    const recorded = p.schedules.filter((s) => s.recorded).length;
    const rounded = Math.round(
        (p.questions.length ? known / p.questions.length : 0) * 30 +
            (docs.length ? ready / docs.length : 0) * 50 +
            (p.schedules.length ? recorded / p.schedules.length : 0) * 20,
    );
    const progress =
        known < p.questions.length ||
        ready < docs.length ||
        recorded < p.schedules.length
            ? Math.min(99, rounded)
            : rounded;
    const warnings = p.questions
        .filter((q) => !p.answers[q.id] || p.answers[q.id] === 'UNKNOWN')
        .map((q) => q.title);
    const unready = docs.find((d) => d.state !== 'READY');
    const next = warnings.length
        ? {
              label: '확인하지 못한 조건 살펴보기',
              reason: '모름·미입력 항목이 남아 있어요.',
              path: '/conditions/edit',
          }
        : unready
          ? {
                label: unready.title + ' 준비하기',
                reason: '아직 준비가 끝나지 않은 서류예요.',
                path: '/documents',
            }
          : {
                label: '신청 일정과 기록 확인하기',
                reason: '공식 절차와 내 기록을 함께 확인하세요.',
                path: '/schedule',
            };
    return {
        ...p,
        progress,
        houseStage:
            progress < 20
                ? 1
                : progress < 40
                  ? 2
                  : progress < 70
                    ? 3
                    : progress < 100
                      ? 4
                      : 5,
        warnings,
        nextAction: { ...next, path: '/preparations/' + p.id + next.path },
    };
}
export async function demoRequest<T>(
    path: string,
    method: string,
    body?: unknown,
): Promise<T> {
    await new Promise((r) => setTimeout(r, 180));
    const url = new URL(path, 'http://demo.local');
    const parts = url.pathname.split('/').filter(Boolean);
    const input = (body ?? {}) as Record<string, unknown>;
    let result: unknown;
    if (path === '/users/me') {
        requireUser();
        result = user;
    } else if (path === '/auth/login') {
        user = {
            id: 'demo',
            name:
                input.email === 'admin@demo.local'
                    ? '시연 관리자'
                    : '시연 사용자',
            email: 'demo@example.invalid',
            role: input.email === 'admin@demo.local' ? 'ADMIN' : 'USER',
        };
        result = { accessToken: 'demo-only', user };
    } else if (path === '/auth/signup') result = undefined;
    else if (path === '/auth/logout') {
        user = null;
        result = undefined;
    } else if (path === '/notices/filters')
        result = {
            regions: ['서울', '경기'],
            types: ['공공임대', '주거비 지원'],
        };
    else if (parts[0] === 'notices' && parts[1]) {
        result = notices.find(
            (n) =>
                n.id === parts[1] &&
                reviews.find((r) => r.id === n.id)?.basic === 'PASS',
        );
        if (!result) throw new ApiError(404, '공고를 찾을 수 없습니다.');
    } else if (parts[0] === 'notices') {
        const q = url.searchParams;
        const keyword = q.get('keyword') ?? '';
        const items = notices.filter(
            (n) =>
                reviews.find((r) => r.id === n.id)?.basic === 'PASS' &&
                [n.title, n.region, n.agency].join(' ').includes(keyword) &&
                ['region', 'type', 'status'].every(
                    (key) =>
                        !q.getAll(key).length ||
                        q
                            .getAll(key)
                            .includes(n[key as 'region' | 'type' | 'status']),
                ),
        );
        items.sort(
            q.get('sort') === 'newest'
                ? (a, b) =>
                      (b.publishedDate ?? '').localeCompare(
                          a.publishedDate ?? '',
                      ) || a.id.localeCompare(b.id)
                : (a, b) =>
                      (a.endDate ?? '').localeCompare(b.endDate ?? '') ||
                      a.id.localeCompare(b.id),
        );
        const page = Math.max(0, Number(q.get('page')) || 0),
            size = 20;
        result = {
            items: items.slice(page * size, (page + 1) * size),
            page,
            size,
            totalElements: items.length,
            totalPages: Math.ceil(items.length / size),
        };
    } else if (parts[0] === 'guides') result = guides;
    else if (parts[0] === 'preparations') {
        requireUser();
        if (parts.length === 1 && method === 'GET') result = preparations;
        else if (parts.length === 1) {
            const n = notices.find((n) => n.id === input.noticeId);
            if (
                !n?.canStart ||
                n.version !== input.version ||
                reviews.find((r) => r.id === n.id)?.basic !== 'PASS'
            )
                throw new ApiError(
                    409,
                    '준비 안내 상태가 변경되었습니다. 공고를 다시 확인하세요.',
                );
            let p = preparations.find((p) => p.noticeId === n.id);
            if (!p) {
                p = calculate({
                    id: crypto.randomUUID(),
                    noticeId: n.id,
                    title: n.title,
                    version: n.version,
                    revision: 1,
                    guideState: n.guideState,
                    questions: n.questions,
                    answers: {},
                    documents: n.documents.map((title, i) => ({
                        id: String(i),
                        title,
                        necessity: i ? 'UNKNOWN' : 'REQUIRED',
                        state: 'UNCHECKED',
                        method: '실제 공고에서 발급 기준과 제출 방법을 확인하세요.',
                        issuer: '공고에서 확인',
                        issueUrl: null,
                        note: '',
                        evidence,
                    })),
                    schedules: [
                        {
                            id: 'apply',
                            title: '공식 신청 여부 확인',
                            date: n.endDate ?? '',
                            recorded: false,
                            evidence,
                        },
                    ],
                    progress: 0,
                    houseStage: 1,
                    warnings: [],
                    nextAction: { label: '', reason: '', path: '' },
                });
                preparations.push(p);
            }
            result = p;
        } else {
            const index = preparations.findIndex((p) => p.id === parts[1]);
            if (index < 0)
                throw new ApiError(404, '준비 기록을 찾을 수 없습니다.');
            let p = preparations[index];
            if (method !== 'GET') {
                if (
                    p.revision !== input.revision ||
                    p.guideState === 'CHANGE_REVIEW'
                )
                    throw new ApiError(
                        409,
                        '기준이 변경되었습니다. 새로 조회한 후 확인해 주세요.',
                    );
                p = structuredClone(p);
                if (parts[2] === 'answers') {
                    p.answers = input.answers as Record<string, Answer>;
                    p.documents[1].necessity =
                        p.answers.house === 'YES'
                            ? 'CONDITIONAL'
                            : p.answers.house === 'NO'
                              ? 'NOT_APPLICABLE'
                              : 'UNKNOWN';
                }
                if (parts[2] === 'documents') {
                    const d = p.documents.find((d) => d.id === parts[3]);
                    if (!d) throw new ApiError(404);
                    if (
                        d.necessity === 'UNKNOWN' ||
                        d.necessity === 'NOT_APPLICABLE'
                    )
                        throw new ApiError(
                            409,
                            '서류 해당 여부를 먼저 확인하세요.',
                        );
                    d.state = input.state as typeof d.state;
                    d.note = String(input.note ?? '');
                }
                if (parts[2] === 'schedules') {
                    const s = p.schedules.find((s) => s.id === parts[3]);
                    if (!s) throw new ApiError(404);
                    s.recorded = Boolean(input.recorded);
                }
                p.revision++;
                preparations[index] = calculate(p);
            }
            result = preparations[index];
        }
    } else if (parts[0] === 'admin') {
        requireUser();
        if (user?.role !== 'ADMIN')
            throw new ApiError(403, '관리자 권한이 필요합니다.');
        if (parts[1] === 'collections') {
            if (method === 'POST')
                collections.unshift({
                    id: crypto.randomUUID(),
                    status: 'SIMULATED',
                    startedAt: new Date().toISOString(),
                    message:
                        '가상 실행 기록입니다. 외부 수집은 수행하지 않았습니다.',
                });
            result = method === 'GET' ? collections : collections[0];
        } else {
            const review = reviews.find((r) => r.id === parts[2]);
            if (method === 'PATCH') {
                if (!review) throw new ApiError(404);
                if (review.revision !== input.revision) throw new ApiError(409);
                Object.assign(review, input, { revision: review.revision + 1 });
                review.notice.canStart =
                    review.basic === 'PASS' && review.guide === 'PASS';
                review.notice.guideState =
                    review.guide === 'PASS'
                        ? 'AVAILABLE'
                        : review.guide === 'UNSUPPORTED'
                          ? 'UNSUPPORTED'
                          : 'REVIEWING';
            }
            result = parts[2] ? review : reviews;
        }
    } else throw new ApiError(404, '시연에서 지원하지 않는 요청입니다.');
    return structuredClone(result) as T;
}

const originalNotices = structuredClone(notices);
export function resetDemo() {
    user = null;
    preparations.length = 0;
    collections.length = 0;
    notices.splice(0, notices.length, ...structuredClone(originalNotices));
    reviews.splice(
        0,
        reviews.length,
        ...notices.map((n) => ({
            id: n.id,
            notice: n,
            basic: 'PASS' as const,
            guide: n.canStart ? ('PASS' as const) : ('HOLD' as const),
            reason: '시연 데이터',
            revision: 1,
        })),
    );
}
