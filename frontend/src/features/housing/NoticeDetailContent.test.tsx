import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import {
    NoticeDetailContent,
    type NoticeDetailTab,
} from './NoticeDetailContent';
import type { Notice, ReviewIssue } from './types';

beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});

const evidence = {
    label: '공고문 3쪽 · 사업 개요',
    url: 'https://apply.lh.or.kr/notice',
    checkedAt: '2026-11-04',
};
const notice: Notice = {
    id: 'notice',
    title: '청년 전세임대 모집',
    agency: 'LH',
    region: '전국',
    type: '청년 전세임대',
    status: 'OPEN',
    startDate: '2026-01-02',
    endDate: '2026-12-31',
    publishedDate: '2025-12-30',
    attachments: [],
    summary:
        '청년을 위한 임대주택 공고입니다. 세부 기준은 공식 공고를 확인하세요.',
    version: 3,
    guideVersion: 3,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence,
    questions: [
        {
            id: 'residence',
            title: '거주지 기준 확인',
            reason: '주민등록상 거주지를 확인해 주세요.',
            evidence,
        },
    ],
    documents: ['주민등록등본'],
};
const reviewIssue: ReviewIssue = {
    id: 'residence-conflict',
    area: 'CONDITION',
    title: '거주 지역 기준',
    detail: '본문과 배점표의 지역이 달라 공식 확인이 필요합니다.',
    questionIds: ['residence'],
    documentIds: ['residence-document'],
    evidence: [evidence],
};
function mount(tab: NoticeDetailTab, overrides: Partial<Notice> = {}) {
    const onTabChange = vi.fn();
    render(
        <MemoryRouter>
            <NoticeDetailContent
                notice={{ ...notice, ...overrides }}
                tab={tab}
                onTabChange={onTabChange}
            />
        </MemoryRouter>,
    );
    return onTabChange;
}

describe('공고 상세 본문', () => {
    it('날짜와 여러 문장으로 된 공고 내용을 자르거나 접지 않고 보여준다', () => {
        const first =
            '2026.10.28. 현재 창원시에 거주하는 무주택세대의 모집입니다.';
        const summary = `${first} 전용 36.5㎡ 주택의 예비입주자를 모집합니다. 기존 대기자의 후순위입니다.`;
        mount('summary', {
            summary,
        });
        for (const sentence of [
            '2026.10.28. 현재 창원시에 사는 무주택세대의 모집이에요.',
            '전용 36.5㎡ 주택의 예비입주자를 모집해요.',
            '기존 대기자의 후순위예요.',
        ]) {
            expect(screen.getByText(sentence)).toBeVisible();
            expect(screen.getByText(sentence).closest('details')).toBeNull();
        }
    });
    it('확인된 조건은 공개하고 충돌 조건의 확인 사유는 펼쳐서 읽는다', async () => {
        mount('conditions', {
            reviewIssues: [reviewIssue],
            questions: [
                ...notice.questions,
                { ...notice.questions[0], id: 'age', title: '연령 기준 확인' },
            ],
        });
        expect(
            screen.getByRole('heading', { name: '연령 기준 확인' }),
        ).toBeVisible();
        const sources = screen.getByText('확인 사유·공식 근거');
        expect(sources.closest('details')).not.toHaveAttribute('open');
        await userEvent.click(sources);
        expect(screen.getByText(reviewIssue.detail)).toBeVisible();
        expect(
            screen.queryByText('검수되지 않은 개인별 안내는 제공하지 않아요.'),
        ).not.toBeInTheDocument();
    });

    it('조건 요약에 수치·단위와 예외를 보존하고 전체 설명과 근거를 다시 열 수 있다', async () => {
        const reason =
            '주택 면적은 36.5㎡ 이하입니다. 다만 5인 이상 가구는 면적 예외가 있습니다. 세대원 수는 2026. 11. 1. 기준으로 확인합니다.';
        mount('conditions', {
            questions: [
                {
                    ...notice.questions[0],
                    title: '주택 면적 기준을 확인했나요?',
                    reason,
                },
            ],
        });
        expect(
            screen.getByRole('heading', { name: '주택 면적 기준' }),
        ).toBeVisible();
        expect(
            screen.getByText('주택 면적은 36.5㎡ 이하 · 예외·별도 기준 있음'),
        ).toBeVisible();
        expect(
            screen.queryByText('다만 5인 이상 가구는 면적 예외가 있어요.'),
        ).not.toBeVisible();
        const toggle = screen.getByLabelText(/전체 조건·예외·공식 근거 보기/);
        expect(toggle.closest('details')).not.toHaveAttribute('open');
        await userEvent.click(toggle);
        for (const text of [
            '주택 면적은 36.5㎡ 이하예요.',
            '다만 5인 이상 가구는 면적 예외가 있어요.',
            '세대원 수는 2026. 11. 1. 기준으로 확인해요.',
        ])
            expect(
                within(toggle.closest('details')!).getByText(text),
            ).toBeVisible();
        expect(
            within(toggle.closest('details')!).getByRole('link'),
        ).toBeVisible();
    });

    it('오산세교2 나이 조건을 펼치면 세 가지 미성년 세대주 예외와 증빙 안내를 구분한다', async () => {
        mount('conditions', {
            questions: [
                {
                    ...notice.questions[0],
                    id: 'adult',
                    title: '공고일 현재 성년자이거나 미성년 신청 예외에 해당하나요?',
                    reason: '공고일 현재 만 19세 이상이 원칙입니다. 미성년 세대주도 같은 등본의 자녀를 부양하거나, 직계존속의 사망·실종·행방불명으로 같은 등본의 형제자매를 부양하거나, 외국인 부모를 둔 내국인 미성년 세대주인 한부모가족이면 예외가 있습니다. 예외는 가족관계 및 법정대리·부양 입증이 필요하므로 추가 확인으로 남깁니다.',
                },
            ],
        });
        const toggle = screen.getByLabelText(/^공고일에 몇 살이어야 하나요\?/);
        const panel = within(toggle.closest('details')!);
        expect(panel.getByRole('list', { hidden: true })).not.toBeVisible();
        await userEvent.click(toggle);
        const rows = panel.getAllByRole('listitem');
        expect(rows).toHaveLength(3);
        expect(rows[0]).toHaveTextContent('같은 등본에 있는 자녀를 부양');
        expect(rows[1]).toHaveTextContent(
            /사망·실종·행방불명으로 같은 등본의 형제자매를 부양/,
        );
        expect(rows[2]).toHaveTextContent(
            /한부모가족.*부모는 외국인.*세대주는 한국 국적/,
        );
        expect(
            panel.getByText(
                /추가 확인 · 예외에 해당하면 가족관계와 법정대리·부양/,
            ),
        ).toBeVisible();
        expect(panel.getByRole('link')).toBeVisible();
    });

    it('오산의 확인된 기준만 나누고 정정되거나 확인 중인 조건은 해당 기록의 설명을 보여준다', async () => {
        const reason =
            '모든 신청자는17쪽 필수 가족관계증명서를 준비합니다. 등본에 피부양자의 배우자가 없거나 예비신혼 세대에 다른 가족을 포함하면 관계 확인용 증명서를 추가합니다.';
        const original = {
            ...notice.questions[0],
            title: '가족관계증명서로 관계를 추가 확인해야 하나요?',
            id: 'family',
            inputKind: 'SINGLE' as const,
            options: [
                { value: 'YES', label: '해당함', requiresFollowUp: false },
                {
                    value: 'NO',
                    label: '해당하지 않음',
                    requiresFollowUp: false,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요 · 추가 확인',
                    requiresFollowUp: true,
                },
            ],
            constraints: null,
            answerDependencies: null,
            applicability: null,
            reason,
            evidence: {
                ...evidence,
                label: '공고문 16–18쪽 · 제출서류',
                url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69203687',
            },
        };
        mount('conditions', {
            questions: [
                original,
                {
                    ...original,
                    id: 'changed',
                    title: '정정된 조건',
                    reason: reason + ' 추가 증빙은 개별 안내합니다.',
                },
                {
                    ...original,
                    id: 'pending',
                    title: '검수 중인 조건',
                    reviewRequired: true,
                },
            ],
        });
        const [first, changed, pending] = screen
            .getAllByLabelText(/공식 근거 보기$/)
            .map((toggle) => toggle.closest('details')!);
        await userEvent.click(first.querySelector('summary')!);
        expect(
            screen.getByRole('heading', {
                name: '가족관계증명서는 언제 더 내야 하나요?',
            }),
        ).toBeVisible();
        expect(
            within(first).getByText('모든 신청자는 가족관계증명서를 준비해요.'),
        ).toBeVisible();
        const page = within(first).getByRole('link', {
            name: '공고문 17쪽 보기',
        });
        expect(
            within(first).queryByText('공고문 16–18쪽 · 제출서류'),
        ).toBeNull();
        expect(page).toHaveAttribute(
            'href',
            '/pdf-viewer.html?file=69203687&page=17&sha=72f277edbb41e30c7c2830eaa21c39670022ad162419781030333c8ba6b29b0f',
        );
        expect(page.closest('div')?.previousElementSibling?.tagName).toBe('UL');
        for (const panel of [changed, pending]) {
            expect(within(panel).queryByText('공고문 17쪽 보기')).toBeNull();
        }
        expect(first.querySelector('dl')).toBeNull();
        expect(within(first).getAllByRole('listitem')).toHaveLength(2);
        expect(within(first).queryByText('예외')).toBeNull();
        expect(changed.querySelector('dl')).toBeNull();
        expect(changed).toHaveTextContent('추가 증빙은 개별 안내해요.');
        expect(pending.querySelector('dl')).toBeNull();
        expect(pending).toHaveTextContent(
            '모든 신청자는17쪽 필수 가족관계증명서를 준비해요.',
        );
        expect(pending).toHaveTextContent(
            '등본에 피부양자의 배우자가 없거나 예비신혼 세대에 다른 가족을 포함하면 관계 확인용 증명서를 추가해요.',
        );
    });

    it('조건 제목과 본문은 근거를 펼치고 열린 근거는 펼침 버튼으로 접는다', async () => {
        mount('conditions');
        const toggle = screen.getByLabelText(
            '거주지 기준 확인 전체 조건·공식 근거 보기',
        );
        const details = toggle.closest('details')!;
        await userEvent.click(screen.getByText('거주지 기준 확인'));
        expect(details).toHaveAttribute('open');
        await userEvent.click(toggle);
        expect(details).not.toHaveAttribute('open');
        await userEvent.click(screen.getByText('주민등록상 거주지 확인'));
        expect(details).toHaveAttribute('open');
        await userEvent.click(toggle);
        expect(details).not.toHaveAttribute('open');
    });
    it('서류 ID별 확인 사유를 보여주며 정상 서류 목록을 유지한다', () => {
        mount('documents', {
            reviewIssues: [reviewIssue],
            documentTemplates: ['residence-document', 'identity-document'].map(
                (id, index) => ({
                    id,
                    title: index ? '신분 확인 서류' : '거주 확인 서류',
                    method: '',
                    issuer: 'LH',
                    issueUrl: null,
                    evidence,
                }),
            ),
        });
        expect(screen.getByText('거주 확인 서류')).toBeVisible();
        expect(screen.getByText('신분 확인 서류')).toBeVisible();
        expect(screen.getAllByText(reviewIssue.detail)).toHaveLength(1);
    });
    it('답변 없이 각 서류의 공통·조건부 제출 대상과 짧은 발급 방법을 표시한다', async () => {
        const base = {
            id: 'common',
            title: '신청서',
            method: '공고일 이후 발급 기준을 확인하세요. 첨부 양식에 서명하세요. 자세한 작성 예시는 별지를 참고하세요.',
            issuer: 'LH',
            issueUrl: null,
            evidence,
        };
        mount('documents', {
            questions: [
                {
                    ...notice.questions[0],
                    id: 'family',
                    options: [
                        { value: 'ENGAGED', label: '예비신혼부부' },
                        { value: 'MARRIED', label: '신혼부부' },
                    ],
                },
            ],
            documentTemplates: [
                base,
                {
                    ...base,
                    id: 'family-form',
                    title: '혼인 계획 확인서',
                    conditionQuestionId: 'family',
                    conditionAnswerValues: ['ENGAGED'],
                },
            ],
        });
        const common = within(screen.getByText('신청서').closest('li')!);
        expect(common.getByText('모든 신청자')).not.toBeVisible();
        await userEvent.click(common.getByText('신청서'));
        expect(common.getByText('모든 신청자')).toBeVisible();
        expect(common.getByText('LH · 첨부 양식에 서명해요.')).toBeVisible();
        const conditional = within(
            screen.getByText('혼인 계획 확인서').closest('li')!,
        );
        expect(conditional.getByText('예비신혼부부')).not.toBeVisible();
        await userEvent.click(conditional.getByText('혼인 계획 확인서'));
        expect(conditional.getByText('예비신혼부부')).toBeVisible();
        await userEvent.click(conditional.getByText('혼인 계획 확인서'));
        expect(conditional.getByText('예비신혼부부')).not.toBeVisible();
        expect(common.getByText('모든 신청자')).toBeVisible();
        expect(conditional.queryByText('신혼부부')).toBeNull();
    });
    it('서류명만 있으면 제출 대상은 원문으로 안내하고 확인된 발급 경로를 표시한다', async () => {
        mount('documents');
        const row = within(screen.getByText('주민등록등본').closest('li')!);
        await userEvent.click(row.getByText('주민등록등본'));
        expect(row.getByText('공식 공고에서 제출 대상 확인')).toBeVisible();
        expect(row.getByRole('link', { name: /정부24/ })).toBeVisible();
    });
    it('검수된 요약 카드에서 조건과 서류 탭으로 이동한다', async () => {
        const change = mount('summary');
        const qualificationToggle =
            screen.getByLabelText('자격과 순위 세부 기준 보기');
        expect(qualificationToggle.closest('details')).not.toHaveAttribute(
            'open',
        );
        await userEvent.click(qualificationToggle);
        await userEvent.click(
            screen.getByRole('button', { name: '조건과 공식 근거 확인' }),
        );
        expect(change).toHaveBeenLastCalledWith('conditions');
        const documentToggle =
            screen.getByLabelText('서류 요약과 제출 기준 보기');
        expect(documentToggle.closest('details')).not.toHaveAttribute('open');
        await userEvent.click(documentToggle);
        expect(documentToggle.closest('details')).toHaveAttribute('open');
        await userEvent.click(
            screen.getByRole('button', { name: '제출서류 확인' }),
        );
        expect(change).toHaveBeenLastCalledWith('documents');
    });

    it('접으면 공통 서류명을 모두 표시하고 펼치면 해당자 서류와 발급 기준도 표시한다', async () => {
        const base = {
            id: 'resident',
            title: '신청자 주민등록표등본',
            issuer: '행정복지센터',
            method: '상세본을 발급하세요. 추가 기재사항은 별지를 따릅니다.',
            issueUrl: null,
            evidence,
        };
        mount('summary', {
            questions: [
                {
                    ...notice.questions[0],
                    options: [
                        {
                            value: 'SEPARATE',
                            label: '배우자와 주소가 다른 신청자',
                        },
                    ],
                },
            ],
            documentTemplates: [
                { ...base, id: 'other', title: '추가 서류' },
                { ...base, id: 'more', title: '다른 공통 서류' },
                {
                    ...base,
                    id: 'extra',
                    title: '추가 증빙',
                    conditionQuestionId: 'residence',
                },
                {
                    ...base,
                    id: 'spouse',
                    title: '분리배우자 주민등록표등본',
                    conditionQuestionId: 'residence',
                    conditionAnswerValues: ['SEPARATE'],
                },
                base,
            ],
            documentSummary: {
                common: {
                    items: [
                        {
                            id: 'resident',
                            title: '신청자 주민등록등본',
                            evidence,
                        },
                    ],
                    remainingCount: 2,
                    remainingIds: ['other', 'more'],
                },
                conditional: {
                    items: [
                        {
                            id: 'spouse',
                            title: '분리배우자 주민등록등본',
                            evidence,
                        },
                    ],
                    remainingCount: 1,
                    remainingIds: ['extra'],
                },
                reviewRequiredCount: 1,
            },
        });
        const common = within(
            screen
                .getByText('신청자 주민등록등본', { selector: 'strong' })
                .closest('li')!,
        );
        const conditional = within(
            screen
                .getByText('분리배우자 주민등록등본', { selector: 'strong' })
                .closest('li')!,
        );
        expect(common.queryByText('제출 대상')).toBeNull();
        expect(conditional.queryByText('발급 방법')).toBeNull();
        expect(screen.queryByText('외 2개')).toBeNull();
        expect(screen.getByText('추가 서류')).toBeVisible();
        expect(screen.getByText('다른 공통 서류')).toBeVisible();
        expect(screen.queryByText('추가 증빙')).toBeNull();
        expect(
            screen.getByText('1개 서류는 제출 기준 확인이 필요해요.'),
        ).not.toBeVisible();
        const details = screen
            .getByLabelText('서류 요약과 제출 기준 보기')
            .closest('details')!;
        expect(
            within(details).getByRole('link', {
                name: /분리배우자 주민등록등본/,
            }),
        ).not.toBeVisible();
        await userEvent.click(
            screen.getByLabelText('서류 요약과 제출 기준 보기'),
        );
        expect(await common.findByText('모든 신청자')).toBeVisible();
        for (const name of ['추가 서류', '다른 공통 서류', '추가 증빙']) {
            expect(
                screen.getByText(name, { selector: 'strong' }),
            ).toBeVisible();
        }
        expect(screen.queryByText(/^외 \d+개$/)).toBeNull();
        expect(
            common.getByText('행정복지센터 · 상세본을 발급해요.'),
        ).toBeVisible();
        expect(
            conditional.getByText('배우자와 주소가 다른 신청자'),
        ).toBeVisible();
        expect(
            within(details).getByRole('link', {
                name: /분리배우자 주민등록등본/,
            }),
        ).toHaveAttribute('href', evidence.url);
        await userEvent.click(
            screen.getByLabelText('서류 요약과 제출 기준 보기'),
        );
        expect(screen.queryByText('추가 증빙')).toBeNull();
        expect(screen.getByText('외 1개')).toBeVisible();
    });

    it('서류 요약이 없는 경우 문서명을 임의로 채우지 않는다', () => {
        mount('summary', { documentSummary: null });
        expect(
            screen.getByText(
                '필요한 서류는 제출서류 탭과 공식 공고에서 확인해요.',
            ),
        ).toBeVisible();
        expect(screen.queryByText('공통 서류')).not.toBeInTheDocument();
    });

    it('서버의 자격·선정 요약은 기본 표시하고 예외와 출처는 접어 둔다', async () => {
        const overview = {
            eligibility: '2자녀 이상인 무주택가구가 대상입니다.',
            selection:
                '거창군 외 거주자 우선, 미달 시 거창군 거주자를 포함합니다.',
            detail: '기존 입주자 특례와 추가 5점은 세부 기준을 따릅니다.',
            evidence,
            reviewRequired: false,
        };
        mount('summary', { qualificationSummary: overview });
        for (const text of [
            '2자녀 이상인 무주택가구가 대상이에요.',
            '거창군 외 거주자 우선, 미달 시 거창군 거주자를 포함해요.',
        ]) {
            expect(screen.getByText(text)).toBeVisible();
            expect(screen.getByText(text).closest('details')).toBeNull();
        }
        expect(
            screen.getByText(
                '기존 입주자 특례와 추가 5점은 세부 기준을 따라요.',
            ),
        ).not.toBeVisible();
        await userEvent.click(
            screen.getByLabelText('자격과 순위 세부 기준 보기'),
        );
        expect(
            screen.getByText(
                '기존 입주자 특례와 추가 5점은 세부 기준을 따라요.',
            ),
        ).toBeVisible();
        expect(
            within(
                screen
                    .getByLabelText('자격과 순위 세부 기준 보기')
                    .closest('details')!,
            ).getByRole('link', { name: /공고문 3쪽/ }),
        ).toHaveAttribute('href', evidence.url);
    });

    it('서버가 확인 필요로 남긴 선정 기준을 임의로 계산하지 않는다', () => {
        mount('summary', {
            qualificationSummary: {
                eligibility: '무주택가구가 대상입니다.',
                selection: '선정 기준은 추가 확인이 필요합니다.',
                detail: null,
                evidence: null,
                reviewRequired: true,
            },
        });
        expect(
            screen.getByText('선정 기준은 추가 확인이 필요해요.'),
        ).toBeVisible();
    });

    it('공고별 요약은 펼쳐 두고 공식 근거만 선택해 열 수 있다', async () => {
        mount('summary');
        expect(
            screen.getByText('청년을 위한 임대주택 공고예요.'),
        ).toBeVisible();
        expect(
            screen.getByText('세부 기준은 공식 공고를 확인해요.'),
        ).toBeVisible();
        const toggle = screen.getByLabelText('지원 내용 공식 근거 보기');
        const details = toggle.closest('details');
        expect(details).not.toHaveAttribute('open');
        expect(screen.getByText(/공고문 3쪽 · 사업 개요/)).not.toBeVisible();
        await userEvent.click(toggle);
        expect(details).toHaveAttribute('open');
        expect(
            screen.getByRole('link', { name: /공고문 3쪽 · 사업 개요/ }),
        ).toHaveAttribute('href', evidence.url);
        await userEvent.click(toggle);
        expect(
            screen.getByText('청년을 위한 임대주택 공고예요.'),
        ).toBeVisible();
    });

    it('검수된 대상과 지원 문장을 구분하고 비용 설명도 접지 않는다', () => {
        mount('summary', {
            summary:
                '무주택 신혼가구가 대상입니다. LH가 계약한 주택을 다시 임대해 줍니다. 본인 부담 보증금은 20%이며 월 임대료는 별도입니다.',
        });
        expect(
            screen.getByText('지원 대상').nextElementSibling,
        ).toHaveTextContent('무주택 신혼가구가 대상이에요.');
        expect(
            screen.getByText('지원 내용').nextElementSibling,
        ).toHaveTextContent('LH가 계약한 주택을 다시 임대해 줘요.');
        expect(
            screen.getByText('함께 확인').nextElementSibling,
        ).toHaveTextContent('본인 부담 보증금은 20%이며 월 임대료는 별도예요.');
        expect(screen.getByText(/본인 부담 보증금은/)).toBeVisible();
    });

    it('신청 자격과 최종 순위를 먼저 보여주고 세부 배점은 유형별 표로 펼친다', async () => {
        const selection =
            '배점이 높은 순 → 오산 전입일이 빠른 순 → 추첨 순서입니다.';
        mount('summary', {
            summary:
                '지원 대상: 오산시 거주 무주택세대구성원 지원 내용: 영구임대주택 공급',
            qualificationSummary: {
                eligibility: '신청 자격은 조건 탭에서 항목별로 확인해 주세요.',
                selection,
                detail: [
                    selection,
                    '신청 자격: 무주택세대구성원',
                    '배점 기준',
                    '유형 | 항목 | 세부 기준 | 배점',
                    '일반공급 | 거주기간 | 1년 미만 | 12점',
                    '일반공급 | 거주기간 | 1년 이상 5년 미만 | 14점',
                    '주거약자형 | 거주기간 | 1년 이상 5년 미만 | 2점',
                    '일반공급 | 가점 | 수급자 | 10점',
                    '주거약자형 | 장애 정도 | 심한 경우 | 3점',
                ].join('\n'),
                evidence,
                reviewRequired: false,
            },
        });
        const card = screen
            .getByRole('heading', { name: '자격과 순위를 구분해요' })
            .closest('section')!;
        const summary = within(card.querySelector('dl')!);
        expect(
            summary.getByText('신청 자격').nextElementSibling,
        ).toHaveTextContent('오산시 거주 무주택세대구성원');
        expect(
            summary.getByText('최종 선정').nextElementSibling,
        ).toHaveTextContent('배점순 · 동점이면 오산 전입일이 빠른 순');
        expect(screen.getByText('기본 배점')).not.toBeVisible();
        await userEvent.click(
            screen.getByLabelText('자격과 순위 세부 기준 보기'),
        );
        expect(within(card).getAllByRole('table')).toHaveLength(3);
        const base = within(screen.getByRole('table', { name: '기본 배점' }));
        const sharedBand = within(
            base.getByRole('row', { name: /1년 이상 5년 미만/ }),
        );
        expect(
            sharedBand.getAllByRole('cell').map((cell) => cell.textContent),
        ).toEqual(['14점', '2점']);
        expect(
            screen.getByRole('table', { name: '일반공급 가점' }),
        ).toHaveTextContent('수급자');
        expect(
            screen.getByRole('table', { name: '주거약자형 가점' }),
        ).toHaveTextContent('장애 정도');
    });
    it.each(['summary', 'conditions', 'documents'] as const)(
        '검수 중인 %s에서는 조건·서류 대신 상태와 공식 경로를 보여준다',
        (tab) => {
            const change = mount(tab, {
                guideState: 'REVIEWING',
                canStart: false,
            });
            expect(
                screen.getByRole('heading', { name: '준비 안내 검수 중' }),
            ).toBeVisible();
            expect(
                screen.getByRole('link', { name: /공식 공고 보기/ }),
            ).toHaveAttribute('href', evidence.url);
            expect(
                screen.queryByText('거주지 기준 확인'),
            ).not.toBeInTheDocument();
            expect(screen.queryByText('주민등록등본')).not.toBeInTheDocument();
            expect(
                screen.queryByRole('heading', {
                    name: /자격과 순위를 구분해요/,
                }),
            ).not.toBeInTheDocument();
            expect(change).not.toHaveBeenCalled();
        },
    );

    it('준비 안내가 검토 중이어도 기본 정보로 공개된 서류 일정을 표시한다', () => {
        mount('schedule', {
            guideState: 'REVIEWING',
            schedules: [
                {
                    id: 'closing',
                    title: '신청 접수 마감',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'documents',
                    title: '서류 접수 마감',
                    date: '2027-01-12',
                    evidence,
                },
            ],
        });
        expect(screen.getByText('접수 마감', { selector: 'dt' })).toBeVisible();
        expect(screen.getAllByText('2027.01.12').length).toBeGreaterThan(0);
        expect(
            screen.queryByRole('checkbox', {
                name: /서류 접수 마감/,
                hidden: true,
            }),
        ).toBeInTheDocument();
        expect(screen.getByText('서류 접수 마감')).toBeInTheDocument();
    });
});
