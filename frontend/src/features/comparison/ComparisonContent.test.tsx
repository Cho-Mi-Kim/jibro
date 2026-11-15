import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ComparisonContent } from './ComparisonContent';
import { comparisonFixture } from './comparisonFixtures';

describe('공고 비교 공개 경계', () => {
    it('검수된 조건·공통/조건부 서류·발급 기준·공식 근거를 표시한다', async () => {
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: comparisonFixture('A') },
                    { slot: 'b', data: comparisonFixture('B') },
                ]}
            />,
        );
        expect(screen.getAllByText('서로 다름')).toHaveLength(4);
        expect(
            screen.getByRole('heading', { name: 'A 거주지 기준' }),
        ).toBeVisible();
        const documents = within(
            screen.getByRole('region', { name: '제출서류' }),
        );
        expect(documents.getAllByText('공통 제출')).toHaveLength(2);
        expect(documents.getAllByText('해당자 제출')).toHaveLength(2);
        expect(documents.getAllByText('공고일 이후 발급한 서류')).toHaveLength(
            2,
        );
        expect(
            documents.getByText('관련 조건: A 거주지 기준 · 서울 거주'),
        ).toBeVisible();
        await userEvent.click(
            screen.getAllByText('조건·선택지·공식 근거 보기')[0],
        );
        expect(screen.getAllByText('서울 거주 / 그 외 지역')[0]).toBeVisible();
        expect(
            screen.getAllByRole('link', { name: /A 공고문 3쪽/ })[0],
        ).toHaveAttribute('href', 'https://apply.lh.or.kr/notice/A');
    });

    it('기본 정보에 지원 대상과 지원 내용을 나누고 달라진 기준에만 차이를 표시한다', () => {
        const a = comparisonFixture('A');
        const b = comparisonFixture('B');
        const audience = '무주택 청년이 대상입니다.';
        const supportA = '시세의 30%로 주택을 공급합니다.';
        const supportB = '시세의 40%로 주택을 공급합니다.';
        const note = '전용면적은 36.5㎡입니다.';
        a.notice.summary = [audience, supportA, note].join(' ');
        b.notice.summary = [audience, supportB].join(' ');
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: a },
                    { slot: 'b', data: b },
                ]}
            />,
        );
        const basic = within(screen.getByRole('region', { name: '기본 정보' }));
        for (const value of basic.getAllByText(audience)) {
            expect(value.previousElementSibling).toHaveTextContent(
                /^지원 대상$/,
            );
        }
        for (const benefit of [supportA, supportB]) {
            expect(
                basic.getByText(benefit).previousElementSibling,
            ).toHaveTextContent('지원 내용서로 다름');
        }
        expect(basic.getByText(note).previousElementSibling).toHaveTextContent(
            '함께 확인',
        );
        expect(basic.queryByText('요약')).toBeNull();
    });

    it('각 공고의 신청 자격과 선정 순위를 나눠 보여주고 해당 공고의 예외와 출처를 펼친다', async () => {
        const a = comparisonFixture('A');
        const b = comparisonFixture('B');
        a.notice.qualificationSummary = {
            eligibility: '무주택 청년이 대상입니다.',
            selection: '지역 거주자가 우선입니다.',
            detail: '같은 순위에서는 추첨합니다.',
            evidence: a.notice.evidence,
            reviewRequired: false,
        };
        b.notice.qualificationSummary = {
            eligibility: '무주택 신혼가구가 대상입니다.',
            selection: '선정 기준은 추가 확인이 필요합니다.',
            detail: null,
            evidence: null,
            reviewRequired: true,
        };
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: a },
                    { slot: 'b', data: b },
                ]}
            />,
        );
        const area = within(
            screen.getByRole('region', { name: '신청 자격과 선정 순위' }),
        );
        for (const { notice } of [a, b]) {
            const column = within(
                area.getByRole('heading', { name: new RegExp(notice.title) })
                    .parentElement!,
            );
            expect(
                column.getByText('신청 자격').nextElementSibling,
            ).toHaveTextContent(notice.qualificationSummary!.eligibility);
            expect(
                column.getByText('선정 순위').nextElementSibling,
            ).toHaveTextContent(notice.qualificationSummary!.selection);
        }
        expect(area.getByText('같은 순위에서는 추첨합니다.')).not.toBeVisible();
        await userEvent.click(area.getByText('세부 기준·공식 근거 보기'));
        expect(area.getByText('같은 순위에서는 추첨합니다.')).toBeVisible();
        expect(
            area.getByRole('link', { name: /A 공고문 3쪽/ }),
        ).toHaveAttribute('href', a.notice.evidence.url);
    });

    it('같은 조건도 공고별 기준을 요약하고 펼친 항목의 전체 설명과 출처를 보여준다', async () => {
        const entries = (['a', 'b'] as const).map((slot) => {
            const data = comparisonFixture(slot.toUpperCase());
            const years = slot === 'a' ? 2 : 3;
            data.notice.questions[0].title = '거주 기간을 확인했나요?';
            data.notice.questions[0].reason = `거주 기간은 ${years}년 이상입니다. 주민등록상 주소를 기준으로 합니다. 제외 기간은 공고문을 확인하세요.`;
            return { slot, data };
        });
        render(<ComparisonContent entries={entries} />);
        const area = within(screen.getByRole('region', { name: '조건' }));
        const [first, second] = area
            .getAllByRole('heading', { name: '거주 기간', level: 4 })
            .map((h) => within(h.closest('li')!));
        expect(first.getByText('거주 기간은 2년 이상')).toBeVisible();
        expect(second.getByText('거주 기간은 3년 이상')).toBeVisible();
        const full = entries[0].data.notice.questions[0].reason;
        expect(first.getByText(full)).not.toBeVisible();
        await userEvent.click(first.getByText('조건·선택지·공식 근거 보기'));
        expect(first.getByText(full)).toBeVisible();
        expect(first.getByRole('link')).toHaveAttribute(
            'href',
            entries[0].data.notice.questions[0].evidence.url,
        );
        expect(
            second.getByText(entries[1].data.notice.questions[0].reason),
        ).not.toBeVisible();
    });

    it('같은 이름의 핵심 서류도 공고별 제출 대상과 발급 방법을 구분한다', async () => {
        const entries = (['a', 'b'] as const).map((slot) => {
            const data = comparisonFixture(slot.toUpperCase());
            const doc = data.documentRequirements![1];
            doc.conditionAnswerValues = [slot === 'a' ? 'SEOUL' : 'OTHER'];
            doc.issuer = slot === 'a' ? '행정복지센터' : '온라인 발급처';
            doc.method = '상세본을 발급하세요. 작성 예시는 별지를 확인하세요.';
            data.notice.documentSummary = {
                common: { items: [], remainingCount: 0 },
                conditional: {
                    items: [
                        {
                            id: doc.id,
                            title: '주소 증빙',
                            evidence: doc.evidence,
                        },
                    ],
                    remainingCount: 1,
                },
                reviewRequiredCount: slot === 'a' ? 1 : 0,
            };
            return { slot, data };
        });
        render(<ComparisonContent entries={entries} />);
        const docs = within(screen.getByRole('region', { name: '제출서류' }));
        const rows = docs
            .getAllByText('주소 증빙')
            .map((title) => within(title.closest('li')!));
        expect(rows).toHaveLength(2);
        expect(rows[0].getByText('서울 거주')).toBeVisible();
        expect(
            rows[0].getByText('행정복지센터 · 상세본을 발급하세요.'),
        ).toBeVisible();
        expect(rows[1].getByText('그 외 지역')).toBeVisible();
        expect(
            rows[1].getByText('온라인 발급처 · 상세본을 발급하세요.'),
        ).toBeVisible();
        expect(
            docs.getByText('1개 서류는 제출 기준 확인이 필요합니다.'),
        ).toBeVisible();
        const [first, second] =
            docs.getAllByText('전체 제출서류와 공식 근거 보기');
        expect(first.closest('details')).not.toHaveAttribute('open');
        await userEvent.click(first);
        expect(
            within(first.closest('details')!).getByText('A 주민등록등본'),
        ).toBeVisible();
        expect(second.closest('details')).not.toHaveAttribute('open');
    });

    it.each(['REVIEWING', 'CHANGE_REVIEW', 'UNSUPPORTED'] as const)(
        '안내 상태 %s에서는 잔존 질문/서류도 숨기고 공개 일정을 유지한다',
        (guideState) => {
            const data = comparisonFixture('A');
            data.notice.guideState = guideState;
            data.notice.qualificationSummary = {
                eligibility: '이전 안내의 신청 자격',
                selection: '이전 안내의 선정 순위',
                detail: null,
                evidence: null,
                reviewRequired: false,
            };
            render(<ComparisonContent entries={[{ slot: 'a', data }]} />);
            expect(
                screen.queryByText('이전 안내의 신청 자격'),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByText('이전 안내의 선정 순위'),
            ).not.toBeInTheDocument();
            expect(screen.queryByText('A 거주지 기준')).not.toBeInTheDocument();
            expect(
                screen.queryByText('A 주민등록등본'),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByText('공고일 이후 발급한 서류'),
            ).not.toBeInTheDocument();
            expect(screen.getByText('2027.01.10')).toBeVisible();
            expect(screen.getByText('2026.12.31 18:00')).toBeVisible();
            expect(
                screen.getAllByText(/해당 항목이 없다는 뜻은 아닙니다/),
            ).toHaveLength(3);
            expect(
                screen.queryByText(/공개된 조건 항목이 없습니다/),
            ).not.toBeInTheDocument();
        },
    );

    it('공개 항목 없음과 서류명만 가진 자료를 구분한다', () => {
        const empty = comparisonFixture('A');
        empty.notice.questions = [];
        empty.documentRequirements = [];
        const titlesOnly = comparisonFixture('B');
        titlesOnly.documentRequirements = null;
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: empty },
                    { slot: 'b', data: titlesOnly },
                ]}
            />,
        );
        expect(screen.getByText(/공개된 조건 항목이 없습니다/)).toBeVisible();
        expect(
            screen.getByText(/공개된 제출서류 항목이 없습니다/),
        ).toBeVisible();
        expect(
            screen.getByText(/현재 자료에는 서류명만 있습니다/),
        ).toBeVisible();
        expect(screen.getByText('B 주민등록등본')).toBeVisible();
        expect(screen.queryByText('공통 제출')).not.toBeInTheDocument();
    });
});
