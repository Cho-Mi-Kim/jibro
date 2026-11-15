import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { EvidenceChecks } from './EvidenceChecks';
import {
    evidenceChecksApi,
    type EvidenceCheckReport,
} from './evidenceChecksApi';

beforeEach(() => {
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
    vi.unstubAllGlobals();
});

const report: EvidenceCheckReport = {
    revision: 2,
    version: 1,
    checkedAt: '2026-11-07T03:00:00Z',
    results: [
        {
            kind: 'CONDITION',
            label: '공고문 4쪽',
            url: 'https://apply.lh.or.kr/file#page=4',
            checkedAt: '2026-11-01',
            status: 'REACHABLE',
            httpStatus: 200,
        },
        {
            kind: 'ISSUANCE',
            label: '소득 증빙서류 · 발급 기관 확인',
            url: null,
            checkedAt: null,
            status: 'MISSING',
            httpStatus: null,
        },
    ],
};

function setup(dirty = false) {
    const client = new QueryClient({
        defaultOptions: { mutations: { retry: false } },
    });
    const component = (revision: number) => (
        <QueryClientProvider client={client}>
            <EvidenceChecks
                noticeId="notice-1"
                revision={revision}
                dirty={dirty}
            />
        </QueryClientProvider>
    );
    return { ...render(component(2)), component, user: userEvent.setup() };
}

it('checks only on request and distinguishes reachability from human verification', async () => {
    const run = vi.spyOn(evidenceChecksApi, 'run').mockResolvedValue(report);
    const { user } = setup();
    expect(run).not.toHaveBeenCalled();
    await user.click(
        screen.getByRole('button', { name: '출처·발급 링크 점검' }),
    );
    expect(await screen.findByText('응답 확인 · 원문 대조 필요')).toBeVisible();
    expect(run).toHaveBeenCalledWith('notice-1', 2);
    expect(
        screen.getByText(/원고에 기록된 출처 확인일 · 2026-11-01/),
    ).toBeVisible();
    expect(
        screen.getByText('주소 미등록 · 발급 기관과 원문 확인 필요'),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: '공고문 4쪽 ↗' })).toHaveAttribute(
        'href',
        'https://apply.lh.or.kr/file#page=4',
    );
});

it('hides a report after the saved revision changes', async () => {
    vi.spyOn(evidenceChecksApi, 'run').mockResolvedValue(report);
    const { user, rerender, component } = setup();
    await user.click(
        screen.getByRole('button', { name: '출처·발급 링크 점검' }),
    );
    await screen.findByText('응답 확인 · 원문 대조 필요');
    rerender(component(3));
    expect(
        screen.queryByText('응답 확인 · 원문 대조 필요'),
    ).not.toBeInTheDocument();
});

it('requires saving changed drafts and surfaces network failure', async () => {
    const run = vi
        .spyOn(evidenceChecksApi, 'run')
        .mockRejectedValue(new Error('서버 연결 확인 필요'));
    const view = setup(true);
    expect(
        screen.getByRole('button', { name: '출처·발급 링크 점검' }),
    ).toBeDisabled();
    expect(run).not.toHaveBeenCalled();
    view.unmount();
    const { user } = setup();
    await user.click(
        screen.getByRole('button', { name: '출처·발급 링크 점검' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '서버 연결 확인 필요',
    );
});
