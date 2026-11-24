import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { Session } from '../features/auth/useSession';
import { api } from '../features/housing/api';
import type {
    DocumentItem,
    Notice,
    Preparation,
    Question,
} from '../features/housing/types';
import { DocumentsPage } from '../pages/DocumentsPage';
import { ConditionResult } from '../features/preparation/ConditionResult';
import { OneTimeCheckResult } from '../features/preparation/OneTimeCheckResult';
import { HistorySnapshot } from '../features/preparation/history/HistorySnapshot';
import { ScheduleTimeline } from '../features/preparation/ScheduleTimeline';
import { ComparisonContent } from '../features/comparison/ComparisonContent';
import { personalizeComparison } from '../features/comparison/comparisonModel';

const evidence = {
    label: '공식 공고문',
    url: 'https://apply.lh.or.kr/',
    checkedAt: '2026-11-01',
};
const question = (id: string, title: string): Question => ({
    id,
    title,
    reason: '공고에서 정한 항목을 확인해요.',
    evidence,
    options: [
        { value: 'YES', label: '내 상황에 해당해요' },
        { value: 'NOT_MY_TYPE', label: '내 상황에 해당하지 않아요' },
        { value: 'UNKNOWN', label: '잘 모르겠어요' },
    ],
});
const document = (
    id: string,
    title: string,
    necessity: DocumentItem['necessity'],
): DocumentItem => ({
    id,
    title,
    necessity,
    state: 'UNCHECKED',
    method: '공식 양식을 준비해요.',
    issuer: 'LH',
    issueUrl: null,
    note: '저장한 메모',
    evidence,
});

function fixture() {
    const p: Preparation = {
        id: 'saved',
        noticeId: 'notice',
        title: '공통 표시 기준 공고',
        version: 1,
        revision: 2,
        guideState: 'AVAILABLE',
        questions: [
            question('common-condition', '공통 확인 항목'),
            question('excluded-condition', '해당 없는 조건 항목'),
            question('unknown-condition', '아직 모르는 조건 항목'),
            {
                ...question('review-condition', '공식 검수 조건 항목'),
                reviewRequired: true,
            },
            {
                id: 'method',
                title: '어떤 방법으로 신청하나요?',
                reason: '접수 방법을 선택해요.',
                evidence,
                options: [
                    { value: 'ONLINE', label: '온라인 신청' },
                    { value: 'VISIT', label: '현장 신청' },
                ],
            },
        ],
        answers: {
            'common-condition': 'YES',
            'excluded-condition': 'NOT_MY_TYPE',
            'unknown-condition': 'UNKNOWN',
            'review-condition': 'NOT_MY_TYPE',
            method: 'ONLINE',
        },
        documents: [
            document('common-document', '공통 준비 서류', 'REQUIRED'),
            document('personal-document', '내 상황 준비 서류', 'CONDITIONAL'),
            document(
                'excluded-document',
                '해당 없는 준비 서류',
                'NOT_APPLICABLE',
            ),
            document('unknown-document', '아직 모르는 준비 서류', 'UNKNOWN'),
            document(
                'review-document',
                '공식 검수 준비 서류',
                'NOT_APPLICABLE',
            ),
        ],
        documentConditions: {
            'personal-document': 'common-condition',
            'excluded-document': 'excluded-condition',
            'unknown-document': 'unknown-condition',
            'review-document': 'review-condition',
        },
        documentConditionValues: {
            'personal-document': ['YES'],
            'excluded-document': ['YES'],
            'unknown-document': ['YES'],
            'review-document': ['YES'],
        },
        reviewIssues: [
            {
                id: 'document-review',
                area: 'DOCUMENT',
                title: '서류 기준 확인',
                detail: '기관에 적용 기준을 확인해요.',
                questionIds: ['review-condition'],
                documentIds: ['review-document'],
                evidence: [evidence],
            },
        ],
        schedules: [
            {
                id: 'online',
                title: '접수 회차 · 온라인 접수 마감',
                date: '2030-11-19T16:00:00',
                recorded: true,
                evidence,
            },
            {
                id: 'visit',
                title: '접수 회차 · 현장 접수 마감',
                date: '2030-11-20T18:00:00',
                recorded: false,
                evidence,
            },
            {
                id: 'result',
                title: '공통 결과 발표',
                date: '2030-11-25',
                recorded: false,
                evidence,
            },
        ],
        progress: 37,
        houseStage: 2,
        warnings: [],
        nextAction: { label: '', reason: '', path: '' },
    };
    const n: Notice = {
        id: p.noticeId,
        title: p.title,
        agency: 'LH',
        region: '서울',
        type: '국민임대',
        status: 'OPEN',
        publishedDate: '2030-11-01',
        startDate: '2030-11-10',
        endDate: '2030-11-20',
        summary: '공고의 지원 내용을 안내해요.',
        attachments: [],
        version: p.version,
        guideVersion: p.version,
        guideState: p.guideState,
        canStart: true,
        evidence,
        questions: p.questions,
        documents: p.documents.map((d) => d.title),
        schedules: p.schedules,
        reviewIssues: p.reviewIssues,
        documentTemplates: p.documents.map((d) => ({
            id: d.id,
            title: d.title,
            method: d.method,
            issuer: d.issuer,
            issueUrl: d.issueUrl,
            evidence: d.evidence,
            conditionQuestionId: p.documentConditions?.[d.id] ?? null,
            conditionAnswerValues: p.documentConditionValues?.[d.id] ?? null,
        })),
    };
    return { p, n };
}

beforeEach(() => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
});
afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
});

it.each(['내 준비', '일회성 결과', '이전 준비 기록', '공고 비교'] as const)(
    '%s는 같은 표시 정책으로 비해당만 제외하고 공통·미확인·공식 검수 항목을 보존한다',
    async (surface) => {
        const { p, n } = fixture();
        const original = structuredClone({ p, n });
        const client = new QueryClient({
            defaultOptions: { queries: { retry: false, gcTime: 0 } },
        });
        client.setQueryData(['preparation', 'owner', p.id], p);
        client.setQueryData(['notice', n.id], n);
        vi.spyOn(api, 'preparation').mockResolvedValue(p);
        vi.spyOn(api, 'notice').mockResolvedValue(n);
        const content =
            surface === '내 준비' ? (
                <>
                    <ConditionResult p={p} inTab />
                    <DocumentsPage />
                    <ScheduleTimeline preparation={p} notice={n} />
                </>
            ) : surface === '일회성 결과' ? (
                <OneTimeCheckResult
                    result={p}
                    templates={n.documentTemplates}
                    schedules={n.schedules}
                    guideState={p.guideState}
                    onEdit={() => {}}
                    onClear={() => {}}
                />
            ) : surface === '이전 준비 기록' ? (
                <HistorySnapshot preparation={p} />
            ) : (
                <ComparisonContent
                    entries={[
                        {
                            slot: 'a',
                            data: personalizeComparison(
                                {
                                    notice: n,
                                    documentRequirements:
                                        n.documentTemplates!.map((d) => ({
                                            ...d,
                                            conditionQuestionId:
                                                d.conditionQuestionId ?? null,
                                            conditionAnswerValues:
                                                d.conditionAnswerValues ?? null,
                                        })),
                                },
                                p,
                            ),
                        },
                    ]}
                />
            );
        const page = render(
            <QueryClientProvider client={client}>
                <Session.Provider
                    value={{
                        user: {
                            id: 'owner',
                            name: '사용자',
                            email: '',
                            role: 'USER',
                        },
                        loading: false,
                        error: null,
                        refresh: async () => {},
                    }}
                >
                    <MemoryRouter
                        initialEntries={['/preparations/saved/documents']}
                    >
                        <Routes>
                            <Route
                                path="/preparations/:preparationId/documents"
                                element={content}
                            />
                        </Routes>
                    </MemoryRouter>
                </Session.Provider>
            </QueryClientProvider>,
        );
        try {
            await screen.findAllByText('공통 준비 서류', { exact: true });
            expect(
                screen.queryByText('해당 없는 준비 서류', { exact: true }),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByText('해당 없는 조건 항목', { exact: true }),
            ).not.toBeInTheDocument();
            for (const title of [
                '공통 확인 항목',
                '아직 모르는 조건 항목',
                '공식 검수 조건 항목',
                '내 상황 준비 서류',
                '아직 모르는 준비 서류',
                '공식 검수 준비 서류',
            ]) {
                expect(
                    screen.queryAllByText(title, { exact: true }).length,
                ).toBeGreaterThan(0);
            }
            {
                expect(
                    screen.queryByText('접수 회차 · 현장 접수 마감', {
                        exact: true,
                    }),
                ).not.toBeInTheDocument();
                for (const title of [
                    '접수 회차 · 온라인 접수 마감',
                    '공통 결과 발표',
                ]) {
                    expect(
                        screen.queryAllByText(title, { exact: true }).length,
                    ).toBeGreaterThan(0);
                }
            }
            expect({ p, n }).toEqual(original);
        } finally {
            page.unmount();
            client.clear();
        }
    },
);
