import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { call } from '../../shared/api/transport';
import { GuidanceReviewPreview } from './GuidanceReviewPreview';
import type { GuidancePreview } from './guidanceReviewApi';

vi.mock('../../shared/api/transport', () => ({ call: vi.fn() }));
const path = '/admin/reviews/notice-1/extractions/draft-1/guidance-preview';
const evidence = {
    label: '모집공고 6쪽',
    url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69296595',
    checkedAt: '2026-11-08',
};
const preview: GuidancePreview = {
    profile: 'national-rental-waiver',
    ready: false,
    candidate: {
        summary: '원문의 적용 대상과 예외를 연결한 준비 안내 초안입니다.',
        evidence,
        attachments: [evidence],
        questions: [
            {
                id: 'household',
                title: '검증 대상 세대구성원을 확인했나요?',
                reason: '분리 배우자와 예외 대상을 함께 확인합니다.',
                options: [
                    { value: 'YES', label: '확인했어요' },
                    {
                        value: 'UNKNOWN',
                        label: '잘 모르겠어요',
                        requiresFollowUp: true,
                    },
                ],
                evidence,
            },
        ],
        documents: [
            {
                id: 'family',
                title: '가족관계증명서',
                conditionQuestionId: 'household',
                conditionAnswerValues: ['YES'],
                method: '공고일 이후 발급분을 확인하세요.',
                issuer: '대법원',
                evidence,
            },
        ],
        schedules: [
            {
                id: 'end',
                title: '신청 접수 마감',
                date: '2026-11-26',
                evidence,
            },
        ],
    },
    issues: ['DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:모집공고.pdf'],
    findings: [
        {
            code: 'FORM_DATE_MISMATCH',
            message: '첨부 양식의 공고일이 본문과 다릅니다.',
            evidence,
        },
    ],
    startDate: '2026-11-25',
    endDate: '2026-11-26',
};
const clients: QueryClient[] = [];
beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
    vi.mocked(call).mockReset();
    vi.mocked(call).mockResolvedValue(preview);
});
afterEach(() => {
    clients.forEach((client) => client.clear());
    clients.length = 0;
    vi.unstubAllGlobals();
});
function setup(initial = { enabled: true, sourceVersion: 'v1' }) {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    clients.push(client);
    const element = (props: typeof initial) => (
        <QueryClientProvider client={client}>
            <GuidanceReviewPreview
                noticeId="notice-1"
                extractionId="draft-1"
                {...props}
            />
        </QueryClientProvider>
    );
    const view = render(element(initial));
    return {
        user: userEvent.setup(),
        rerender: (props: typeof initial) => view.rerender(element(props)),
    };
}

describe('관리자 준비 안내 초안 검토', () => {
    it('요청할 때만 읽기 조회하고 공개 보류 이유와 생성된 내용을 함께 보여 준다', async () => {
        const { user } = setup();
        expect(call).not.toHaveBeenCalled();
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(call).toHaveBeenCalledExactlyOnceWith(path);
        expect(await screen.findByText('공개 보류')).toBeVisible();
        expect(
            screen.getByText('첨부 양식의 공고일이 본문과 다릅니다.'),
        ).toBeVisible();
        expect(
            screen.getByText(
                '같은 공고의 문서 형식별 내용이 일치하는지 확인해야 합니다.',
            ),
        ).toBeVisible();
        expect(screen.getByLabelText('생성된 초안 항목 수')).toHaveTextContent(
            '질문 1개서류 1개일정 1개',
        );
        expect(
            screen.queryByRole('button', { name: /공개|저장|반영/ }),
        ).not.toBeInTheDocument();

        await user.click(screen.getByText('생성된 질문 보기'));
        expect(
            screen.getByRole('heading', {
                name: '검증 대상 세대구성원을 확인했나요?',
            }),
        ).toBeVisible();
        expect(screen.getByText('잘 모르겠어요 · 추가 확인')).toBeVisible();
        await user.click(screen.getByText('생성된 서류 보기'));
        expect(
            screen.getByText('연결 질문 · 검증 대상 세대구성원을 확인했나요?'),
        ).toBeVisible();
        expect(screen.getByText('제출 대상 답변 · 확인했어요')).toBeVisible();
        await user.click(screen.getByText('생성된 일정 보기'));
        expect(
            screen.getByText('접수기간 · 2026-11-25 ~ 2026-11-26'),
        ).toBeVisible();
        expect(
            screen.getAllByRole('link', { name: '모집공고 6쪽 ↗' })[0],
        ).toHaveAttribute('href', evidence.url);
        expect(call).toHaveBeenCalledTimes(1);
    });

    it('원고 편집·구버전 등 비활성 상태는 조회도 이전 결과 표시도 하지 않는다', async () => {
        const { user, rerender } = setup({
            enabled: false,
            sourceVersion: 'v1',
        });
        expect(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        ).toBeDisabled();
        expect(call).not.toHaveBeenCalled();
        rerender({ enabled: true, sourceVersion: 'v1' });
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(await screen.findByText('공개 보류')).toBeVisible();
        rerender({ enabled: false, sourceVersion: 'v1' });
        expect(screen.queryByText('공개 보류')).not.toBeInTheDocument();
        expect(call).toHaveBeenCalledTimes(1);
    });

    it('같은 발췌 ID라도 첨부나 확인 시각이 바뀌면 이전 초안을 재사용하지 않는다', async () => {
        const { user, rerender } = setup();
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(await screen.findByText('공개 보류')).toBeVisible();
        rerender({ enabled: true, sourceVersion: 'v2-new-source-hash' });
        expect(screen.queryByText('공개 보류')).not.toBeInTheDocument();
        expect(call).toHaveBeenCalledTimes(1);
        vi.mocked(call).mockResolvedValue({
            ...preview,
            ready: true,
            issues: [],
            findings: [],
        });
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(await screen.findByText('자동 검수 조건 충족')).toBeVisible();
        expect(call).toHaveBeenCalledTimes(2);
    });

    it('조회 오류는 자동 재시도하지 않고 같은 버튼으로 다시 확인한다', async () => {
        vi.mocked(call).mockRejectedValueOnce(
            new Error('현재 발췌와 원고가 일치하지 않습니다.'),
        );
        const { user } = setup();
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '현재 발췌와 원고가 일치하지 않습니다.',
        );
        expect(call).toHaveBeenCalledTimes(1);
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(await screen.findByText('공개 보류')).toBeVisible();
        expect(call).toHaveBeenCalledTimes(2);
    });

    it('생성 실패를 빈 초안으로 오인하지 않고 안전하지 않은 출처 링크를 만들지 않는다', async () => {
        vi.mocked(call).mockResolvedValue({
            ...preview,
            candidate: null,
            findings: [
                {
                    ...preview.findings[0],
                    evidence: { ...evidence, url: 'javascript:alert(1)' },
                },
            ],
        });
        const { user } = setup();
        await user.click(
            screen.getByRole('button', { name: '준비 안내 초안 검토' }),
        );
        expect(
            await screen.findByText(
                /아직 질문·서류·일정 초안을 만들지 못했습니다/,
            ),
        ).toBeVisible();
        expect(
            screen.queryByLabelText('생성된 초안 항목 수'),
        ).not.toBeInTheDocument();
        expect(screen.queryByRole('link')).not.toBeInTheDocument();
    });
});
