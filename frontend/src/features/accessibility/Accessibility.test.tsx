import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AccessibilityProvider } from './AccessibilityProvider';
import { AccessibilityPage } from './AccessibilityPage';
import { parsePreferences, prefersReducedMotion } from './preferences';

function openSettings() {
    return render(
        <MemoryRouter>
            <AccessibilityProvider>
                <AccessibilityPage />
            </AccessibilityProvider>
        </MemoryRouter>,
    );
}

describe('device accessibility preferences', () => {
    beforeEach(() => {
        localStorage.clear();
        vi.stubGlobal(
            'ResizeObserver',
            class {
                observe() {}
                unobserve() {}
                disconnect() {}
            },
        );
    });
    afterEach(() => vi.restoreAllMocks());

    it('applies choices immediately, retains them after reopening, and resets all options', async () => {
        const user = userEvent.setup();
        const first = openSettings();
        await user.click(screen.getByLabelText('다크 모드'));
        await user.click(screen.getByLabelText('아주 크게'));
        await user.click(screen.getByRole('checkbox', { name: /대비 강화/ }));
        await user.click(
            screen.getByRole('checkbox', { name: /화면 움직임 줄이기/ }),
        );
        expect(document.documentElement).toHaveAttribute(
            'data-text-size',
            'extra',
        );
        expect(document.documentElement).toHaveAttribute(
            'data-high-contrast',
            'true',
        );
        expect(prefersReducedMotion()).toBe(true);
        expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
        first.unmount();
        expect(document.documentElement).not.toHaveAttribute('data-theme');
        openSettings();
        expect(screen.getByLabelText('다크 모드')).toBeChecked();
        expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
        expect(screen.getByLabelText('아주 크게')).toBeChecked();
        expect(
            screen.getByRole('checkbox', { name: /대비 강화/ }),
        ).toBeChecked();
        await user.click(
            screen.getByRole('button', { name: '기본 설정으로 되돌리기' }),
        );
        expect(screen.getByLabelText('기본')).toBeChecked();
        expect(screen.getByLabelText('라이트 모드')).toBeChecked();
        expect(document.documentElement).toHaveAttribute('data-theme', 'light');
        expect(document.documentElement).toHaveAttribute(
            'data-high-contrast',
            'false',
        );
        expect(document.documentElement).toHaveAttribute(
            'data-reduce-motion',
            'false',
        );
        expect(
            JSON.parse(localStorage.getItem('jibro.accessibility.v1')!),
        ).toEqual({
            theme: 'light',
            textSize: 'default',
            highContrast: false,
            reduceMotion: false,
        });
    });

    it('supports keyboard selection and changes made in another tab', async () => {
        const user = userEvent.setup();
        openSettings();
        screen.getByLabelText('라이트 모드').focus();
        await user.keyboard('{ArrowRight}');
        expect(screen.getByLabelText('다크 모드')).toBeChecked();
        expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
        screen.getByLabelText('기본').focus();
        await user.keyboard('{ArrowRight}');
        expect(screen.getByLabelText('크게')).toBeChecked();
        act(() =>
            window.dispatchEvent(
                new StorageEvent('storage', {
                    key: 'jibro.accessibility.v1',
                    newValue: '{"textSize":"extra","highContrast":true}',
                }),
            ),
        );
        expect(screen.getByLabelText('아주 크게')).toBeChecked();
        expect(screen.getByLabelText('라이트 모드')).toBeChecked();
        act(() =>
            window.dispatchEvent(
                new StorageEvent('storage', {
                    key: 'jibro.accessibility.v1',
                    newValue:
                        '{"theme":"dark","textSize":"extra","highContrast":true}',
                }),
            ),
        );
        expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
        expect(screen.getByLabelText('아주 크게')).toBeChecked();
        act(() =>
            window.dispatchEvent(
                new StorageEvent('storage', { key: null, newValue: null }),
            ),
        );
        expect(screen.getByLabelText('기본')).toBeChecked();
        expect(screen.getByLabelText('라이트 모드')).toBeChecked();
    });

    it('keeps usable defaults for invalid storage and truthfully reports storage failure', () => {
        localStorage.setItem(
            'jibro.accessibility.v1',
            '{"theme":"unknown","textSize":"huge","highContrast":"true"}',
        );
        openSettings();
        expect(screen.getByLabelText('기본')).toBeChecked();
        expect(screen.getByLabelText('라이트 모드')).toBeChecked();
        expect(
            screen.queryByRole('region', { name: '설정 미리 보기' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByText('설정은 이 브라우저에 자동으로 저장됩니다.'),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('checkbox', { name: /대비 강화/ }),
        ).not.toBeChecked();
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new DOMException('Blocked', 'SecurityError');
        });
        fireEvent.click(screen.getByLabelText('크게'));
        expect(document.documentElement).toHaveAttribute(
            'data-text-size',
            'large',
        );
        expect(screen.getByRole('status')).toHaveTextContent(
            '이번 화면에만 적용',
        );
        fireEvent.click(screen.getByLabelText('다크 모드'));
        expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
        expect(document.documentElement).toHaveAttribute(
            'data-text-size',
            'large',
        );
    });

    it('keeps legacy preferences and rejects invalid theme values without changing other valid choices', () => {
        expect(
            parsePreferences(
                '{"textSize":"large","highContrast":true,"reduceMotion":true}',
            ),
        ).toEqual({
            theme: 'light',
            textSize: 'large',
            highContrast: true,
            reduceMotion: true,
        });
        expect(parsePreferences('{"theme":true,"textSize":"extra"}')).toEqual({
            theme: 'light',
            textSize: 'extra',
            highContrast: false,
            reduceMotion: false,
        });
    });

    it('always respects system reduced motion even when the site option is off', () => {
        vi.stubGlobal(
            'matchMedia',
            vi.fn(() => ({ matches: true })),
        );
        openSettings();
        expect(
            screen.getByRole('checkbox', { name: /화면 움직임 줄이기/ }),
        ).not.toBeChecked();
        expect(prefersReducedMotion()).toBe(true);
    });
});
