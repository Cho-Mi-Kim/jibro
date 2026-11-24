import { afterEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import {
    NoticeVisits,
    useNoticeListPosition,
    type Visits,
} from './NoticeListVisit';

afterEach(() => vi.unstubAllGlobals());

function ComparisonPosition({ ready }: { ready: boolean }) {
    const { list } = useNoticeListPosition<HTMLDivElement>(ready);
    return (
        <div ref={list} aria-label="비교 내용">
            <section data-expanded="true" />
        </div>
    );
}

it.each(['/compare', '/saved/compare'])(
    '%s 복귀는 내용이 준비될 때 한 번 복원하고 이후 재조회가 사용자의 새 스크롤을 덮어쓰지 않는다',
    (pathname) => {
        vi.stubGlobal('scrollY', 0);
        const scroll = vi.fn((options: ScrollToOptions) => {
            vi.stubGlobal('scrollY', options.top);
        });
        vi.stubGlobal('scrollTo', scroll);
        const visits: Visits = {
            lists: new Map([
                [
                    'prior-comparison',
                    {
                        pathname,
                        search: '?ids=one,two',
                        top: 317,
                        scrollSpace: '240px',
                        toolbarRevealed: true,
                    },
                ],
            ]),
            details: new Map(),
        };
        const view = (ready: boolean) => (
            <MemoryRouter
                initialEntries={[
                    {
                        pathname,
                        search: '?ids=one,two',
                        state: { comparisonKey: 'prior-comparison' },
                    },
                ]}
            >
                <NoticeVisits.Provider value={visits}>
                    <ComparisonPosition ready={ready} />
                </NoticeVisits.Provider>
            </MemoryRouter>
        );
        const page = render(view(false));
        expect(scroll).not.toHaveBeenCalled();
        page.rerender(view(true));
        expect(window.scrollY).toBe(317);
        expect(
            screen
                .getByLabelText('비교 내용')
                .style.getPropertyValue('--notice-scroll-space'),
        ).toBe('240px');
        vi.stubGlobal('scrollY', 700);
        page.rerender(view(false));
        page.rerender(view(true));
        expect(window.scrollY).toBe(700);
        expect(scroll).toHaveBeenCalledTimes(1);
    },
);
