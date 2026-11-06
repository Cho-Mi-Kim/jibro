import {
    demoMember,
    resetDemoMember,
    isDemoArchived,
    demoFacts,
} from '../member/demoMember';
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
import {
    answerDemoPreparation,
    assessDemoPreparation,
    demoDocumentNecessity,
    type DemoDocumentRule,
} from './demoPreparationRules';
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
const documentTemplates = [
    { id: '0', title: '거주 확인 서류' },
    { id: '1', title: '조건별 확인 서류' },
];
const documentRules: Readonly<Record<string, DemoDocumentRule>> = {
    '1': { questionId: 'house', answerValues: ['YES'] },
};
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
        state === 'AVAILABLE' ? documentTemplates.map((d) => d.title) : [],
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
function currentPreparation(p: Preparation): Preparation {
    const notice = notices.find((n) => n.id === p.noticeId);
    const published =
        reviews.find((r) => r.id === p.noticeId)?.basic === 'PASS';
    return assessDemoPreparation({
        ...p,
        guideState:
            !notice || !published || notice.version !== p.version
                ? 'CHANGE_REVIEW'
                : notice.guideState,
    });
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
    if (parts[0] === 'member') {
        requireUser();
        result = demoMember(
            parts,
            method,
            input,
            user!,
            notices.filter(
                (n) => reviews.find((r) => r.id === n.id)?.basic === 'PASS',
            ),
            preparations,
        );
    } else if (parts[0] === 'notices' && parts[2] === 'versions') {
        const n = notices.find(
            (n) =>
                n.id === parts[1] &&
                reviews.find((r) => r.id === n.id)?.basic === 'PASS',
        );
        if (!n) throw new ApiError(404);
        result = [
            { version: n.version, revision: n.version, facts: demoFacts(n) },
        ];
    } else if (path === '/users/me') {
        requireUser();
        result = user;
    } else if (path === '/auth/login') {
        const demoId =
            input.email === 'admin@demo.local' ? 'demo-admin' : 'demo';
        if (user && user.id !== demoId) {
            preparations.length = 0;
            resetDemoMember();
        }
        user = {
            id: demoId,
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
        preparations.length = 0;
        resetDemoMember();
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
        if (parts.length === 1 && method === 'GET')
            result = preparations
                .filter((p) => !isDemoArchived(p.id))
                .map(currentPreparation);
        else if (parts.length === 1) {
            let p = preparations.find((p) => p.noticeId === input.noticeId);
            if (p && isDemoArchived(p.id))
                throw new ApiError(
                    409,
                    '보관함에서 이 준비 건을 복원해 주세요.',
                    'ARCHIVED',
                );
            if (!p) {
                const n = notices.find((n) => n.id === input.noticeId);
                if (
                    !n?.canStart ||
                    n.status === 'CLOSED' ||
                    n.version !== input.version ||
                    reviews.find((r) => r.id === n.id)?.basic !== 'PASS'
                )
                    throw new ApiError(
                        409,
                        '준비 안내 상태가 변경되었습니다. 공고를 다시 확인하세요.',
                    );
                p = assessDemoPreparation({
                    id: crypto.randomUUID(),
                    noticeId: n.id,
                    title: n.title,
                    version: n.version,
                    revision: 1,
                    guideState: n.guideState,
                    questions: n.questions,
                    answers: {},
                    documents: documentTemplates.map(({ id, title }) => ({
                        id,
                        title,
                        necessity: demoDocumentNecessity(documentRules[id], {}),
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
            result = currentPreparation(p);
        } else {
            const index = preparations.findIndex((p) => p.id === parts[1]);
            if (index < 0 || isDemoArchived(parts[1]))
                throw new ApiError(404, '준비 기록을 찾을 수 없습니다.');
            let p = currentPreparation(preparations[index]);
            if (method !== 'GET') {
                if (
                    p.revision !== input.revision ||
                    p.guideState !== 'AVAILABLE'
                )
                    throw new ApiError(
                        409,
                        '기준이 변경되었습니다. 새로 조회한 후 확인해 주세요.',
                    );
                p = structuredClone(p);
                if (parts[2] === 'answers') {
                    p = answerDemoPreparation(
                        p,
                        input.answers as Record<string, Answer>,
                        documentRules,
                    );
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
                    if (
                        ![
                            'UNCHECKED',
                            'NEEDED',
                            'IN_PROGRESS',
                            'READY',
                            'RECHECK',
                        ].includes(String(input.state)) ||
                        typeof input.note !== 'string' ||
                        input.note.length > 2000
                    )
                        throw new ApiError(
                            400,
                            '준비 상태와 메모를 다시 확인해 주세요.',
                        );
                    d.state = input.state as typeof d.state;
                    d.note = input.note;
                }
                if (parts[2] === 'schedules') {
                    const s = p.schedules.find((s) => s.id === parts[3]);
                    if (!s) throw new ApiError(404);
                    s.recorded = Boolean(input.recorded);
                }
                if (!['answers', 'documents', 'schedules'].includes(parts[2]))
                    throw new ApiError(
                        404,
                        '시연에서 지원하지 않는 요청입니다.',
                    );
                p.revision++;
                preparations[index] = assessDemoPreparation(p);
            }
            result = currentPreparation(preparations[index]);
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
    resetDemoMember();
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
