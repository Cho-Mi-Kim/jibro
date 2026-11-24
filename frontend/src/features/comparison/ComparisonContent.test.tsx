import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ComparisonContent } from './ComparisonContent';
import { comparisonFixture } from './comparisonFixtures';
import * as scrolling from '../../shared/ui/animateScroll';

describe('공고 비교 공개 경계', () => {
    beforeEach(() => {
        vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    });
    afterEach(() => vi.restoreAllMocks());
    it('표시 범위가 빈 서류와 이름을 확인할 수 없는 생략 서류를 구분한다', async () => {
        const a = comparisonFixture('A');
        a.documentRequirements = [];
        a.notice.documentSummary = {
            common: { items: [], remainingCount: 0 },
            conditional: { items: [], remainingCount: 0 },
            reviewRequiredCount: 0,
        };
        const b = comparisonFixture('B');
        b.documentRequirements = [];
        b.notice.documentSummary = {
            common: { items: [], remainingCount: 0 },
            conditional: { items: [], remainingCount: 2 },
            reviewRequiredCount: 0,
        };
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: a },
                    { slot: 'b', data: b },
                ]}
            />,
        );
        expect(
            screen.getByText(/표시할 제출서류 항목이 없습니다/),
        ).toBeVisible();
        expect(screen.getByText('외 2개')).toBeVisible();
        await userEvent.click(
            screen.getByText('전체 제출서류와 공식 근거 보기'),
        );
        expect(
            await screen.findByText(/생략된 서류의 세부 목록은 공식 공고/),
        ).toBeVisible();
    });
    it('두 번째 공고의 조건·서류와 세부 일정을 여닫으면 각각 해당 카드로 정렬한다', async () => {
        const user = userEvent.setup();
        const positions: number[] = [];
        vi.spyOn(scrolling, 'animateScroll').mockImplementation((_, top) => {
            positions.push(typeof top === 'function' ? top() : top);
            return () => {};
        });
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: comparisonFixture('A') },
                    { slot: 'b', data: comparisonFixture('B') },
                ]}
            />,
        );
        async function toggle(
            card: HTMLElement,
            trigger: HTMLElement,
            top: number,
        ) {
            vi.spyOn(card, 'getBoundingClientRect').mockReturnValue(
                new DOMRect(0, top, 360, 150),
            );
            positions.length = 0;
            await user.click(trigger);
            await waitFor(() => expect(positions).toEqual([top - 12]));
        }
        for (const title of ['B 거주지 기준', 'B 주민등록등본']) {
            const heading = screen.getByRole('heading', { name: title });
            const item = heading.closest('li')!;
            await toggle(item, heading, 240);
            await toggle(item, item, 380);
        }
        const summary = screen.getAllByText('세부 일정·공식 근거 보기')[1];
        const column = summary.closest('details')!.parentElement!;
        await toggle(column, summary, 300);
        const event = within(column)
            .getByRole('heading', { name: '공고일' })
            .closest('li')!;
        await toggle(event, event, 520);
        await toggle(event, event, 190);
        await toggle(column, summary, 300);
    });
    it('조건·서류·일정 카드의 본문과 여백은 누른 항목만 여닫는다', async () => {
        const user = userEvent.setup();
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: comparisonFixture('A') },
                    { slot: 'b', data: comparisonFixture('B') },
                ]}
            />,
        );
        for (const title of ['A 거주지 기준', 'A 주민등록등본']) {
            const heading = screen.getByRole('heading', { name: title });
            const card = heading.closest('li')!;
            await user.click(heading);
            expect(card.querySelector('details')).toHaveAttribute('open');
            await user.click(
                within(card.querySelector('details')!).getByRole('link', {
                    name: /A 공고문 3쪽/,
                }),
            );
            expect(card.querySelector('details')).toHaveAttribute('open');
            await user.click(card);
            expect(card.querySelector('details')).not.toHaveAttribute('open');
        }
        const schedule = within(
            screen.getByRole('region', { name: '공식 일정' }),
        );
        const [first, second] = schedule
            .getAllByText('세부 일정·공식 근거 보기')
            .map((summary) => summary.closest('details')!);
        await user.click(schedule.getAllByText('접수 기간')[0]);
        expect(first.open).toBe(true);
        expect(second.open).toBe(false);
        const event = within(first)
            .getByRole('heading', { name: '공고일' })
            .closest('li')!;
        await user.click(event);
        expect(event.querySelector('details')).toHaveAttribute('open');
        await user.click(event);
        expect(event.querySelector('details')).not.toHaveAttribute('open');
        expect(first.open).toBe(true);
        await user.click(first.parentElement!);
        expect(first.open).toBe(false);
    });
    it('LH 약칭과 정식 명칭은 같은 로고로 표시하고 다른 기관은 자체 이름으로 구분한다', () => {
        const a = comparisonFixture('A');
        const b = comparisonFixture('B');
        b.notice.agency = '한국토지주택공사';
        const entries = [
            { slot: 'a', data: a },
            { slot: 'b', data: b },
        ] as const;
        const { rerender } = render(
            <ComparisonContent entries={[...entries]} />,
        );
        const basic = within(screen.getByRole('region', { name: '기본 정보' }));
        expect(basic.getAllByRole('img', { name: 'LH' })).toHaveLength(2);
        for (const row of basic.getAllByText('운영기관')) {
            expect(row).not.toHaveTextContent('서로 다름');
        }
        b.notice.agency = 'LH·지역공사 공동모집';
        rerender(<ComparisonContent entries={[...entries]} />);
        expect(basic.getAllByRole('img', { name: 'LH' })).toHaveLength(1);
        expect(
            basic.getByText(b.notice.agency).previousElementSibling,
        ).toHaveTextContent('운영기관서로 다름');
    });
    it('모집 지역과 주택 유형을 표시값으로 비교하고 누락된 유형을 알린다', () => {
        const a = comparisonFixture('A');
        const b = comparisonFixture('B');
        a.notice.region = '대구·경북';
        b.notice.region = '전국';
        a.notice.type = '';
        b.notice.type = '   ';
        render(
            <ComparisonContent
                entries={[
                    { slot: 'a', data: a },
                    { slot: 'b', data: b },
                ]}
            />,
        );
        const basic = within(screen.getByRole('region', { name: '기본 정보' }));
        for (const region of ['대구·경북', '전국']) {
            expect(
                basic.getByText(region).previousElementSibling,
            ).toHaveTextContent('모집 지역서로 다름');
        }
        for (const label of basic.getAllByText('주택 유형')) {
            expect(label).not.toHaveTextContent('서로 다름');
            expect(label.nextElementSibling).toHaveTextContent('확인 필요');
        }
    });
    it('공식 조기 마감과 준비 안내의 일부 확인 상태를 각각 표시한다', async () => {
        const data = comparisonFixture('A');
        data.notice.lifecycle = {
            state: 'EARLY_CLOSED',
            reason: '',
            checkedAt: null,
            evidence: null,
            revision: 1,
        };
        data.notice.reviewIssues = [
            {
                id: 'one',
                area: 'CONDITION',
                title: '확인 항목',
                detail: '',
                questionIds: [],
                documentIds: [],
                evidence: [],
            },
        ];
        render(<ComparisonContent entries={[{ slot: 'a', data }]} />);
        const basic = within(screen.getByRole('region', { name: '기본 정보' }));
        expect(
            basic.getByText('접수 상태').nextElementSibling,
        ).toHaveTextContent('조기 마감');
        const badge = basic.getByRole('button', {
            name: '준비 안내 제공 · 일부 확인 필요',
        });
        expect(badge).toHaveAttribute('data-tone', 'amber');
        await userEvent.click(badge);
        expect(badge).toHaveAttribute('aria-expanded', 'true');
        await userEvent.keyboard('{Escape}');
        expect(badge).toHaveAttribute('aria-expanded', 'false');
    });
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
        const common = within(
            documents.getByText('A 주민등록등본').closest('li')!,
        );
        expect(common.getByText('제출 대상')).not.toBeVisible();
        await userEvent.click(common.getByText('A 주민등록등본'));
        expect(common.getByText('공통 제출')).toBeVisible();
        expect(common.getByText(/공고일 이후 발급한 서류/)).toBeVisible();
        const extra = within(documents.getByText('A 추가 서류').closest('li')!);
        await userEvent.click(extra.getByText('A 추가 서류'));
        expect(extra.getByText('해당자 제출')).toBeVisible();
        expect(extra.getByText('관련 조건: A 거주지 기준')).toBeVisible();
        await userEvent.click(
            screen.getAllByLabelText(/조건·선택지·공식 근거 보기$/)[0],
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
        for (const value of basic.getAllByText('무주택 청년이 대상이에요.')) {
            expect(value.previousElementSibling).toHaveTextContent(
                /^지원 대상$/,
            );
        }
        for (const benefit of [
            '시세의 30%로 주택을 공급해요.',
            '시세의 40%로 주택을 공급해요.',
        ]) {
            expect(
                basic.getByText(benefit).previousElementSibling,
            ).toHaveTextContent('지원 내용서로 다름');
        }
        expect(
            basic.getByText('전용면적은 36.5㎡예요.').previousElementSibling,
        ).toHaveTextContent('함께 확인');
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
        for (const { notice, eligibility, selection } of [
            {
                notice: a.notice,
                eligibility: '무주택 청년이 대상이에요.',
                selection: '지역 거주자가 우선이에요.',
            },
            {
                notice: b.notice,
                eligibility: '무주택 신혼가구가 대상이에요.',
                selection: '선정 기준은 추가 확인이 필요해요.',
            },
        ]) {
            const column = within(area.getByTitle(notice.title).parentElement!);
            expect(
                column.getByText('신청 자격').nextElementSibling,
            ).toHaveTextContent(eligibility);
            expect(
                column.getByText('선정 순위').nextElementSibling,
            ).toHaveTextContent(selection);
        }
        expect(area.getByText('같은 순위에서는 추첨해요.')).not.toBeVisible();
        await userEvent.click(area.getByText('세부 기준·공식 근거 보기'));
        expect(area.getByText('같은 순위에서는 추첨해요.')).toBeVisible();
        expect(
            area.getByRole('link', { name: /A 공고문 3쪽/ }),
        ).toHaveAttribute('href', a.notice.evidence.url);
    });

    it('같은 조건도 공고별 기준을 요약하고 펼친 항목의 전체 설명과 출처를 보여준다', async () => {
        const entries = (['a', 'b'] as const).map((slot) => {
            const data = comparisonFixture(slot.toUpperCase());
            const limit = slot === 'a' ? '5,451' : '3,708';
            data.notice.questions[0].title =
                '세대 전체의 자동차 기준을 확인했나요?';
            data.notice.questions[0].reason = `자동차 기준은 완화하지 않습니다. 비영업용 승용자동차별 가액 중 최고 가액이 ${limit}만원 이하인지 확인합니다. 배우자 등 다른 세대원의 차량도 포함합니다.`;
            return { slot, data };
        });
        render(<ComparisonContent entries={entries} />);
        const area = within(screen.getByRole('region', { name: '조건' }));
        const [first, second] = area
            .getAllByRole('heading', {
                name: '우리 집 자동차 기준은 얼마인가요?',
                level: 4,
            })
            .map((h) => within(h.closest('li')!));
        expect(
            first.getByText(
                '영업용이 아닌 승용차 중 가장 비싼 차가 5,451만 원 이하',
            ),
        ).toBeVisible();
        expect(
            second.getByText(
                '영업용이 아닌 승용차 중 가장 비싼 차가 3,708만 원 이하',
            ),
        ).toBeVisible();
        const explanation = /배우자 등 다른 세대원의 차도 함께 봐요/;
        expect(first.getByText(explanation)).not.toBeVisible();
        await userEvent.click(
            first.getByLabelText(/조건·선택지·공식 근거 보기$/),
        );
        expect(first.getByText(explanation)).toBeVisible();
        expect(first.getByRole('link')).toHaveAttribute(
            'href',
            entries[0].data.notice.questions[0].evidence.url,
        );
        expect(second.getByText(explanation)).not.toBeVisible();
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
        expect(rows[0].getByText('서울 거주')).not.toBeVisible();
        await userEvent.click(rows[0].getByText('주소 증빙'));
        expect(rows[0].getByText('서울 거주')).toBeVisible();
        expect(rows[1].getByText('그 외 지역')).not.toBeVisible();
        await userEvent.click(rows[1].getByText('주소 증빙'));
        expect(
            rows[0].getByText('행정복지센터 · 상세본을 발급해요.'),
        ).toBeVisible();
        expect(rows[1].getByText('그 외 지역')).toBeVisible();
        expect(
            rows[1].getByText('온라인 발급처 · 상세본을 발급해요.'),
        ).toBeVisible();
        expect(
            docs.getByText('1개 서류는 제출 기준 확인이 필요해요.'),
        ).toBeVisible();
        const [first, second] =
            docs.getAllByText('전체 제출서류와 공식 근거 보기');
        expect(first.closest('details')).not.toHaveAttribute('open');
        await userEvent.click(first);
        const expanded = within(first.closest('details')!);
        expect(expanded.getByText('서울 거주')).not.toBeVisible();
        await userEvent.click(expanded.getByText('A 추가 서류'));
        expect(expanded.getByText('서울 거주')).toBeVisible();
        expect(
            expanded.getByText('행정복지센터 · 상세본을 발급해요.'),
        ).toBeVisible();
        expect(
            within(first.closest('details')!).getByText('A 주민등록등본'),
        ).toBeVisible();
        expect(second.closest('details')).not.toHaveAttribute('open');
    });

    it('전체 서류를 펼친 공고만 공통·해당자 서류를 빠짐없이 한 번씩 보여준다', async () => {
        const entries = (['a', 'b'] as const).map((slot) => {
            const data = comparisonFixture(slot);
            const [common, conditional] = data.documentRequirements!;
            const moreCommon = {
                ...common,
                id: 'more-common',
                title: slot + ' 추가 공통 서류',
            };
            const moreConditional = {
                ...conditional,
                id: 'more-conditional',
                title: slot + ' 추가 조건부 서류',
            };
            data.documentRequirements!.push(moreCommon, moreConditional);
            data.notice.documentSummary = {
                common: {
                    items: [common],
                    remainingIds: [moreCommon.id],
                    remainingCount: 1,
                },
                conditional: {
                    items: [conditional],
                    remainingIds: [moreConditional.id],
                    remainingCount: 1,
                },
                reviewRequiredCount: 0,
            };
            return { slot, data };
        });
        render(<ComparisonContent entries={entries} />);
        const area = within(screen.getByRole('region', { name: '제출서류' }));
        const [first, second] =
            area.getAllByText('전체 제출서류와 공식 근거 보기');
        const a = within(first.closest('details')!);
        expect(area.getByText('a 추가 공통 서류')).toBeVisible();
        expect(area.getByText('b 추가 공통 서류')).toBeVisible();
        expect(area.getAllByText('외 1개')).toHaveLength(2);
        await userEvent.click(first);
        for (const [heading, extra] of [
            ['공통 서류', 'a 추가 공통 서류'],
            ['해당 시 추가 서류', 'a 추가 조건부 서류'],
        ]) {
            const group = within((await a.findByText(heading)).parentElement!);
            expect(group.getAllByRole('listitem')).toHaveLength(2);
            expect(group.getByText(extra)).toBeVisible();
        }
        expect(second.closest('details')).not.toHaveAttribute('open');
        expect(area.getByText('외 1개')).toBeVisible();
        await userEvent.click(second);
        expect(await area.findByText('b 추가 조건부 서류')).toBeVisible();
        await userEvent.click(first);
        await waitFor(() =>
            expect(area.queryByText('a 추가 조건부 서류')).toBeNull(),
        );
        expect(area.getByText('외 1개')).toBeVisible();
        expect(area.getByText('a 추가 공통 서류')).toBeVisible();
        expect(area.getByText('b 추가 공통 서류')).toBeVisible();
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
            expect(
                screen.getByText('결과 발표', { selector: 'dt' })
                    .nextElementSibling,
            ).toHaveTextContent('2027.01.10');
            expect(
                screen.getByText('접수 기간', { selector: 'dt' })
                    .nextElementSibling,
            ).toHaveTextContent(/2026.12.31.*18:00까지/);
            expect(
                screen.getAllByText(/해당 항목이 없다는 뜻은 아니에요/),
            ).toHaveLength(3);
            expect(
                screen.queryByText(/표시할 조건 항목이 없습니다/),
            ).not.toBeInTheDocument();
        },
    );

    it('공개 항목 없음과 서류명만 가진 자료를 구분한다', async () => {
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
        expect(screen.getByText(/표시할 조건 항목이 없습니다/)).toBeVisible();
        expect(
            screen.getByText(/표시할 제출서류 항목이 없습니다/),
        ).toBeVisible();
        expect(
            screen.getByText(/현재 자료에는 서류명만 있습니다/),
        ).toBeVisible();
        expect(screen.getByText('B 주민등록등본')).toBeVisible();
        const titleOnly = within(
            screen.getByText('B 주민등록등본').closest('li')!,
        );
        await userEvent.click(titleOnly.getByText('B 주민등록등본'));
        expect(
            titleOnly.getByText('공식 공고에서 제출 대상 확인'),
        ).toBeVisible();
        expect(
            titleOnly.getByText('정부24 주민등록표 발급', { selector: 'dd' }),
        ).toBeVisible();
        expect(screen.queryByText('공통 제출')).not.toBeInTheDocument();
    });
});
