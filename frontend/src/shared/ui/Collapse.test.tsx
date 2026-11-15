import { act, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Collapse } from './Collapse';

afterEach(() => vi.useRealTimers());
it('keeps closing content inert for 750ms, then removes it', () => {
    vi.useFakeTimers();
    const view = render(
        <Collapse open>
            <button>내용</button>
        </Collapse>,
    );
    view.rerender(
        <Collapse open={false}>
            <button>내용</button>
        </Collapse>,
    );
    expect(screen.getByText('내용').closest('[inert]')).not.toBeNull();
    act(() => vi.advanceTimersByTime(749));
    expect(screen.getByText('내용')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByText('내용')).not.toBeInTheDocument();
});
it('cancels removal when opened again during closing', () => {
    vi.useFakeTimers();
    const view = render(<Collapse open>내용</Collapse>);
    view.rerender(<Collapse open={false}>내용</Collapse>);
    act(() => vi.advanceTimersByTime(300));
    view.rerender(<Collapse open>내용</Collapse>);
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByText('내용').closest('[data-open]')).toHaveAttribute(
        'data-open',
        'true',
    );
});
