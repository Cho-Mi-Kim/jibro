import { fireEvent, render, screen, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it } from 'vitest';
import { AnalyticsConsent } from './AnalyticsConsent';
import {
    AnalyticsClient,
    analyticsSettingsEvent,
    readAnalyticsConsent,
} from './analytics';

beforeEach(() => {
    localStorage.clear();
    document.querySelector('#jibro-analytics')?.remove();
});

it('lets a visitor reject, reopen settings, allow and withdraw collection', () => {
    render(
        <MemoryRouter>
            <AnalyticsConsent
                client={
                    new AnalyticsClient(
                        'G-UI123',
                        'https://jibro.live',
                        'GTM-UI123',
                    )
                }
            />
        </MemoryRouter>,
    );
    expect(
        screen.getByRole('region', { name: '방문 통계 설정' }),
    ).toBeVisible();
    expect(document.querySelector('#jibro-analytics')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '필수 기능만' }));
    expect(
        screen.queryByRole('region', { name: '방문 통계 설정' }),
    ).not.toBeInTheDocument();
    expect(readAnalyticsConsent()).toBe('denied');
    act(() => window.dispatchEvent(new Event(analyticsSettingsEvent)));
    fireEvent.click(screen.getByRole('button', { name: '통계 허용' }));
    expect(readAnalyticsConsent()).toBe('granted');
    expect(document.querySelector('#jibro-analytics')).not.toBeNull();
    act(() => window.dispatchEvent(new Event(analyticsSettingsEvent)));
    fireEvent.click(screen.getByRole('button', { name: '필수 기능만' }));
    expect(readAnalyticsConsent()).toBe('denied');
});

it('does not load tracking or display consent prompts when it is not configured', () => {
    render(
        <MemoryRouter>
            <AnalyticsConsent
                client={new AnalyticsClient('', 'https://jibro.live', '')}
            />
        </MemoryRouter>,
    );
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    act(() => window.dispatchEvent(new Event(analyticsSettingsEvent)));
    expect(
        screen.getByText('현재 방문 통계 수집을 사용하지 않아요.'),
    ).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
});
