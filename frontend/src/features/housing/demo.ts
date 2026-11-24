import { demoNoticeListItem } from './demoNoticeListItem';
import { validatedFollowUp } from './followUp';
import { canRecordSchedule } from './schedules';
import {
    demoMember,
    resetDemoMember,
    isDemoArchived,
    demoFacts,
} from '../member/demoMember';
import type {
    Answer,
    Collection,
    DocumentTemplate,
    Notice,
    Preparation,
    Review,
    User,
} from './types';
import { ApiError } from '../../shared/api/client';
import { waitForDemoResponse } from './demoDelay';
import { documentNeedsReview } from './reviewIssueSelectors';
import { dependentDocumentNecessity } from './answerDependencies';
import { DEFAULT_NOTICE_SORT, noticeComparator } from './noticeOrdering';
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
const documentTemplates: DocumentTemplate[] = [
    { id: '0', title: '거주 확인 서류' },
    {
        id: '1',
        title: '조건별 확인 서류',
        conditionQuestionId: 'house',
        conditionAnswerValues: ['YES'],
    },
].map((document) => ({
    ...document,
    method: '실제 공고에서 발급 기준과 제출 방법을 확인하세요.',
    issuer: '공고에서 확인',
    issueUrl: null,
    evidence,
}));
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
function requireUser() {
    if (!user) throw new ApiError(401, '로그인이 필요합니다.');
}
function demoSchedules(n: Notice) {
    return (
        n.schedules?.map((schedule) => ({ ...schedule, recorded: false })) ?? [
            {
                id: 'apply',
                title: '공식 신청 여부 확인',
                date: n.endDate ?? '',
                recorded: false,
                evidence,
            },
        ]
    );
}
function preparationDocumentRules(
    p: Preparation,
): Record<string, DemoDocumentRule> {
    return Object.fromEntries(
        Object.entries(p.documentConditions ?? {}).map(([id, questionId]) => [
            id,
            {
                questionId,
                answerValues: p.documentConditionValues?.[id] ?? ['YES'],
            },
        ]),
    );
}
function createDemoPreparation(n: Notice): Preparation {
    const templates = n.documentTemplates ?? documentTemplates;
    const rules = Object.fromEntries(
        templates.flatMap((template) =>
            template.conditionQuestionId
                ? [
                      [
                          template.id,
                          {
                              questionId: template.conditionQuestionId,
                              answerValues: template.conditionAnswerValues ?? [
                                  'YES',
                              ],
                          },
                      ],
                  ]
                : [],
        ),
    );
    return assessDemoPreparation({
        id: crypto.randomUUID(),
        noticeId: n.id,
        title: n.title,
        version: n.version,
        revision: 1,
        guideState: n.guideState,
        reviewIssues: n.reviewIssues,
        questions: structuredClone(n.questions),
        answers: {},
        documentConditions: Object.fromEntries(
            Object.entries(rules).map(([id, rule]) => [id, rule.questionId]),
        ),
        documentConditionValues: Object.fromEntries(
            Object.entries(rules).map(([id, rule]) => [
                id,
                [...rule.answerValues],
            ]),
        ),
        documents: templates.map((template) => ({
            id: template.id,
            title: template.title,
            necessity: dependentDocumentNecessity(
                demoDocumentNecessity(rules[template.id], {}, n.questions),
                template.id,
                n.questions,
                {},
            ),
            state: 'UNCHECKED',
            method: template.method,
            issuer: template.issuer,
            issueUrl: template.issueUrl,
            issueLabel: template.issueLabel,
            note: '',
            evidence: template.evidence,
        })),
        schedules: demoSchedules(n),
        progress: 0,
        houseStage: 1,
        warnings: [],
        nextAction: { label: '', reason: '', path: '' },
    });
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
    await waitForDemoResponse();
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
    } else if (
        parts[0] === 'notices' &&
        parts[2] === 'check-once' &&
        method === 'POST'
    ) {
        const n = notices.find(
            (notice) =>
                notice.id === parts[1] &&
                reviews.find((review) => review.id === notice.id)?.basic ===
                    'PASS',
        );
        if (!n) throw new ApiError(404);
        if (
            !n.canStart ||
            n.guideState !== 'AVAILABLE' ||
            n.version !== input.version
        )
            throw new ApiError(
                409,
                '공고 안내가 바뀌었습니다. 공고를 다시 확인해 주세요.',
            );
        const draft = createDemoPreparation(n);
        const checked = assessDemoPreparation(
            answerDemoPreparation(
                draft,
                input.answers as Record<string, Answer>,
                preparationDocumentRules(draft),
            ),
        );
        result = {
            noticeId: n.id,
            title: n.title,
            version: n.version,
            questions: checked.questions,
            answers: checked.answers,
            documents: checked.documents,
            warnings: checked.warnings,
            reviewIssues: checked.reviewIssues,
        };
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
        const notice = notices.find(
            (n) =>
                n.id === parts[1] &&
                reviews.find((r) => r.id === n.id)?.basic === 'PASS',
        );
        if (!notice) throw new ApiError(404, '공고를 찾을 수 없습니다.');
        result = notice.canStart
            ? { ...notice, schedules: demoSchedules(notice) }
            : notice;
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
                        (key === 'region' && n.region === '전국') ||
                        q
                            .getAll(key)
                            .includes(n[key as 'region' | 'type' | 'status']),
                ),
        );
        items.sort(
            noticeComparator(
                q.get('sort') ?? DEFAULT_NOTICE_SORT,
                q.getAll('region'),
            ),
        );
        const page = Math.max(0, Number(q.get('page')) || 0),
            size = 20;
        result = {
            items: items
                .slice(page * size, (page + 1) * size)
                .map(demoNoticeListItem),
            page,
            size,
            totalElements: items.length,
            totalPages: Math.ceil(items.length / size),
        };
    } else if (parts[0] === 'guides') {
        const { demoGuides } = await import('../guide/demoGuides');
        result = demoGuides(url.searchParams);
    } else if (parts[0] === 'preparations') {
        requireUser();
        if (parts.length === 1 && method === 'GET')
            result = preparations
                .filter((p) => !isDemoArchived(p.id))
                .map(currentPreparation)
                .map(
                    ({ id, noticeId, title, version, revision, progress }) => ({
                        id,
                        noticeId,
                        title,
                        version,
                        revision,
                        progress,
                    }),
                );
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
                p = createDemoPreparation(n);
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
                    (p.guideState !== 'AVAILABLE' && parts[2] !== 'follow-up')
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
                        preparationDocumentRules(p),
                    );
                }
                if (parts[2] === 'documents') {
                    const d = p.documents.find((d) => d.id === parts[3]);
                    if (!d) throw new ApiError(404);
                    if (
                        documentNeedsReview(d, p.reviewIssues) ||
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
                    const nextState = input.state as typeof d.state;
                    if (nextState !== d.state) {
                        d.stateBeforeReady =
                            nextState === 'READY' && d.state !== 'READY'
                                ? d.state
                                : undefined;
                        d.state = nextState;
                    }
                    d.note = input.note;
                }
                if (parts[2] === 'schedules') {
                    const s = p.schedules.find((s) => s.id === parts[3]);
                    if (!s) throw new ApiError(404);
                    if (!canRecordSchedule(s, p.schedules))
                        throw new ApiError(
                            400,
                            '이 접수 일정은 완료로 기록하지 않아요.',
                        );
                    s.recorded = Boolean(input.recorded);
                }
                if (parts[2] === 'follow-up')
                    p.followUp = validatedFollowUp(input);
                if (
                    ![
                        'answers',
                        'documents',
                        'schedules',
                        'follow-up',
                    ].includes(parts[2])
                )
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
