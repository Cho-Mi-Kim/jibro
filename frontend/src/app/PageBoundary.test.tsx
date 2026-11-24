import { lazy, useState, type ReactNode } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { PageBoundary } from './PageBoundary';

function Navigation() {
    return (
        <nav aria-label="주 메뉴">
            <Link to="/">홈</Link>
            <Link to="/guide">가이드</Link>
        </nav>
    );
}

function SharedLayout({ children }: { children: ReactNode }) {
    const [selected, setSelected] = useState(false);
    return (
        <>
            <button onClick={() => setSelected(!selected)}>
                {selected ? '공고 선택됨' : '공고 선택'}
            </button>
            {children}
        </>
    );
}

describe('화면 코드 로딩 경계', () => {
    it('코드를 기다리는 동안 메뉴를 유지하고 로딩이 끝나면 실제 화면을 표시한다', async () => {
        let complete!: (module: { default: () => ReactNode }) => void;
        const pageModule = new Promise<{ default: () => ReactNode }>(
            (resolve) => {
                complete = resolve;
            },
        );
        const DeferredPage = lazy(() => pageModule);
        render(
            <MemoryRouter>
                <Navigation />
                <PageBoundary>
                    <DeferredPage />
                </PageBoundary>
            </MemoryRouter>,
        );

        expect(screen.getByRole('status')).toHaveTextContent(
            '내용을 불러오고 있어요',
        );
        expect(
            within(screen.getByRole('navigation')).getByRole('link', {
                name: '가이드',
            }),
        ).toBeVisible();

        await act(async () => {
            complete({ default: () => <h1>공고 목록</h1> });
            await pageModule;
        });
        expect(
            await screen.findByRole('heading', { name: '공고 목록' }),
        ).toBeVisible();
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
    });

    it('화면 코드 다운로드 실패를 알리고 다른 화면으로 이동하면 복구한다', async () => {
        const consoleError = vi
            .spyOn(console, 'error')
            .mockImplementation(() => {});
        const FailedPage = lazy(() =>
            Promise.reject(
                new Error('Failed to fetch dynamically imported module'),
            ),
        );
        try {
            render(
                <MemoryRouter>
                    <Navigation />
                    <PageBoundary>
                        <Routes>
                            <Route path="/" element={<FailedPage />} />
                            <Route
                                path="/guide"
                                element={<h1>주거 준비 가이드</h1>}
                            />
                        </Routes>
                    </PageBoundary>
                </MemoryRouter>,
            );
            const alert = within(await screen.findByRole('alert'));
            expect(
                alert.getByRole('heading', {
                    name: '화면을 불러오지 못했어요',
                }),
            ).toBeVisible();
            expect(
                alert.getByRole('button', { name: '새로고침' }),
            ).toBeEnabled();

            await userEvent.click(
                within(screen.getByRole('navigation')).getByRole('link', {
                    name: '가이드',
                }),
            );
            expect(
                await screen.findByRole('heading', {
                    name: '주거 준비 가이드',
                }),
            ).toBeVisible();
            expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        } finally {
            consoleError.mockRestore();
        }
    });

    it('정상 화면 이동 때는 경계 안의 공유 선택 상태를 초기화하지 않는다', async () => {
        render(
            <MemoryRouter>
                <Navigation />
                <PageBoundary>
                    <SharedLayout>
                        <Routes>
                            <Route path="/" element={<h1>공고 목록</h1>} />
                            <Route
                                path="/guide"
                                element={<h1>주거 준비 가이드</h1>}
                            />
                        </Routes>
                    </SharedLayout>
                </PageBoundary>
            </MemoryRouter>,
        );
        await userEvent.click(
            screen.getByRole('button', { name: '공고 선택' }),
        );
        await userEvent.click(screen.getByRole('link', { name: '가이드' }));

        expect(
            await screen.findByRole('heading', { name: '주거 준비 가이드' }),
        ).toBeVisible();
        expect(
            screen.getByRole('button', { name: '공고 선택됨' }),
        ).toBeVisible();
    });
});
