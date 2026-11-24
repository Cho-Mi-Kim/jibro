import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
    act,
    cleanup,
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react';
import { useState } from 'react';
import { VisitorGate } from './VisitorGate';
import { call } from '../../shared/api/transport';
import { visitorVerificationRequiredEvent } from '../../shared/api/client';

vi.mock('../../shared/api/transport', () => ({
    call: vi.fn(),
    demoMode: false,
}));
vi.mock('./TurnstileWidget', () => ({
    TurnstileWidget: ({ onToken }: { onToken: (value: string) => void }) => (
        <button onClick={() => onToken('browser-token')}>사람 확인</button>
    ),
}));
const request = vi.mocked(call);
const needed = { enabled: true, verified: false, siteKey: 'site-key' };
beforeEach(() => {
    request.mockReset();
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
});
afterEach(cleanup);

it('통과 전에는 앱을 실행하지 않고 서버가 쿠키를 확인한 뒤 원래 화면을 표시한다', async () => {
    const mount = vi.fn();
    function Content() {
        mount();
        return <p>기존 페이지</p>;
    }
    request
        .mockResolvedValueOnce(needed)
        .mockResolvedValueOnce(undefined)
        .mockResolvedValueOnce({ ...needed, verified: true });
    render(
        <VisitorGate>
            <Content />
        </VisitorGate>,
    );
    fireEvent.click(await screen.findByRole('button', { name: '사람 확인' }));
    expect(mount).not.toHaveBeenCalled();
    expect(await screen.findByText('기존 페이지')).toBeVisible();
    expect(request).toHaveBeenNthCalledWith(2, '/security/visitor', 'POST', {
        token: 'browser-token',
    });
    expect(screen.queryByRole('button', { name: '사람 확인' })).toBeNull();
});

it('기존 통과 기록과 비활성 설정에서는 확인 위젯을 표시하지 않는다', async () => {
    request.mockResolvedValue({ enabled: false, verified: true, siteKey: '' });
    render(
        <VisitorGate>
            <p>페이지</p>
        </VisitorGate>,
    );
    expect(await screen.findByText('페이지')).toBeVisible();
    expect(screen.queryByRole('button', { name: '사람 확인' })).toBeNull();
});

it('검증 거절·쿠키 차단 시 앱을 열지 않고 다시 시도할 수 있다', async () => {
    request
        .mockResolvedValueOnce(needed)
        .mockRejectedValueOnce(new Error('확인을 다시 진행해 주세요.'));
    render(
        <VisitorGate>
            <p>페이지</p>
        </VisitorGate>,
    );
    fireEvent.click(await screen.findByRole('button', { name: '사람 확인' }));
    expect(await screen.findByText('확인을 다시 진행해 주세요.')).toBeVisible();
    expect(
        await screen.findByRole('button', { name: '다시 시도' }),
    ).toBeVisible();
    expect(screen.queryByText('페이지')).toBeNull();
    request
        .mockResolvedValueOnce(needed)
        .mockResolvedValueOnce(undefined)
        .mockResolvedValueOnce(needed);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(3));
    fireEvent.click(screen.getByRole('button', { name: '사람 확인' }));
    expect(
        await screen.findByText('쿠키를 허용한 뒤 다시 시도해 주세요.'),
    ).toBeVisible();
    expect(screen.queryByText('페이지')).toBeNull();
});

it('통과 기록이 만료돼도 입력 중인 내용은 유지하고 저장 요청은 자동 재전송하지 않는다', async () => {
    function Content() {
        const [value, setValue] = useState('');
        return (
            <input
                aria-label="메모"
                value={value}
                onChange={(event) => setValue(event.target.value)}
            />
        );
    }
    request.mockResolvedValueOnce({ ...needed, verified: true });
    render(
        <VisitorGate>
            <Content />
        </VisitorGate>,
    );
    const input = await screen.findByRole('textbox', { name: '메모' });
    fireEvent.change(input, { target: { value: '작성 중인 메모' } });
    request
        .mockResolvedValueOnce(needed)
        .mockResolvedValueOnce(undefined)
        .mockResolvedValueOnce({ ...needed, verified: true });
    act(() => {
        window.dispatchEvent(new Event(visitorVerificationRequiredEvent));
        window.dispatchEvent(new Event(visitorVerificationRequiredEvent));
    });
    fireEvent.click(await screen.findByRole('button', { name: '사람 확인' }));
    await waitFor(() => expect(input).toBeVisible());
    expect(input).toHaveValue('작성 중인 메모');
    expect(
        request.mock.calls.filter(([, method]) => method === 'POST'),
    ).toHaveLength(1);
});
