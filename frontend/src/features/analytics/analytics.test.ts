import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
    AnalyticsClient,
    analyticsScreen,
    readAnalyticsConsent,
    saveAnalyticsConsent,
} from './analytics';

const testWindow = window as unknown as Window & {
    dataLayer?: IArguments[];
    gtag?: (...args: unknown[]) => void;
    [key: `ga-disable-${string}`]: boolean;
};
const entries = () =>
    (testWindow.dataLayer ?? []).map((entry) => Array.from(entry));

beforeEach(() => {
    localStorage.clear();
    delete testWindow.dataLayer;
    delete testWindow.gtag;
    document.querySelector('#jibro-analytics')?.remove();
});
afterEach(() => {
    vi.restoreAllMocks();
});

describe('optional analytics boundary', () => {
    it('does not load or queue a tag before consent, after denial, or outside production', () => {
        const client = new AnalyticsClient(
            'G-TEST123',
            'https://jibro.live',
            'GTM-TEST123',
        );
        client.update(null, '/');
        client.update('denied', '/');
        new AnalyticsClient(
            'G-TEST123',
            'http://127.0.0.1:5173',
            'GTM-TEST123',
        ).update('granted', '/');
        new AnalyticsClient(
            'invalid',
            'https://jibro.live',
            'GTM-TEST123',
        ).update('granted', '/');
        new AnalyticsClient('G-TEST123', 'https://jibro.live', '').update(
            'granted',
            '/',
        );
        expect(document.querySelector('#jibro-analytics')).toBeNull();
        expect(entries()).toEqual([]);
        expect(testWindow.gtag).toBeUndefined();
    });
    it('only sends public screen categories with no personal URL, query, referrer or identifier', () => {
        window.history.replaceState(
            {},
            '',
            '/notices/sensitive-id?email=private@example.com#token',
        );
        const client = new AnalyticsClient(
            'G-TEST123',
            'https://jibro.live',
            'GTM-TEST123',
        );
        client.update('granted', '/notices/sensitive-id');
        client.update('granted', '/notices/different-id');
        expect(entries().filter((entry) => entry[0] === 'event')).toEqual([
            [
                'event',
                'page_view',
                {
                    send_to: 'G-TEST123',
                    page_location: 'https://jibro.live/notices/detail',
                    page_title: '공고 상세',
                    page_referrer: '',
                },
            ],
        ]);
        expect(JSON.stringify(entries())).not.toMatch(
            /sensitive-id|different-id|private@example|token/,
        );
        expect(
            document.querySelector<HTMLScriptElement>('#jibro-analytics')
                ?.referrerPolicy,
        ).toBe('no-referrer');
        const config = entries().find((entry) => entry[0] === 'set')?.[1];
        const grantIndex = entries().findIndex(
            (entry) =>
                entry[0] === 'consent' &&
                entry[1] === 'update' &&
                (entry[2] as { analytics_storage?: string })
                    .analytics_storage === 'granted',
        );
        expect(grantIndex).toBeLessThan(
            entries().findIndex((entry) => entry[0] === 'set'),
        );
        expect(config).toMatchObject({
            send_page_view: false,
            allow_google_signals: false,
            allow_ad_personalization_signals: false,
            cookie_update: false,
        });
        const script =
            document.querySelector<HTMLScriptElement>('#jibro-analytics')!;
        expect(script.src).toBe(
            'https://www.googletagmanager.com/gtm.js?id=GTM-TEST123',
        );
        expect(entries().some((entry) => entry[0] === 'config')).toBe(false);
        expect(testWindow.gtag).toBeTypeOf('function');
        expect(script.dataset.loadState).toBe('loading');
        script.dispatchEvent(new Event('load'));
        expect(script.dataset.loadState).toBe('loaded');
        script.dispatchEvent(new Event('error'));
        expect(script.dataset.loadState).toBe('failed');
    });
    it('disables collection on private routes and on withdrawal; clears first party GA cookies', () => {
        const client = new AnalyticsClient(
            'G-TEST123',
            'https://jibro.live',
            'GTM-TEST123',
        );
        client.update('granted', '/');
        const eventCount = entries().filter(
            (entry) => entry[0] === 'event',
        ).length;
        for (const route of [
            '/preparations/secret',
            '/my',
            '/login',
            '/auth/callback',
            '/saved',
            '/archive',
            '/notices/id/check',
            '/unknown',
        ]) {
            client.update('granted', route);
            expect(testWindow['ga-disable-G-TEST123']).toBe(true);
        }
        expect(entries().filter((entry) => entry[0] === 'event')).toHaveLength(
            eventCount,
        );
        document.cookie = '_ga=private; path=/';
        client.update('denied', '/');
        expect(testWindow['ga-disable-G-TEST123']).toBe(true);
        expect(document.cookie).not.toContain('_ga=');
        expect(entries().at(-1)).toEqual([
            'consent',
            'update',
            { analytics_storage: 'denied' },
        ]);
    });
    it('stores a time limited choice and rejects expired, malformed or future values', () => {
        saveAnalyticsConsent('denied');
        expect(readAnalyticsConsent()).toBe('denied');
        saveAnalyticsConsent('granted');
        expect(readAnalyticsConsent()).toBe('granted');
        for (const value of [
            'invalid',
            JSON.stringify({ value: 'granted', at: 0 }),
            JSON.stringify({ value: 'granted', at: Date.now() + 999999 }),
        ]) {
            localStorage.setItem('jibro:analytics-consent:v1', value);
            expect(readAnalyticsConsent()).toBeNull();
        }
    });
    it('keeps sensitive and unknown routes out of the screen allowlist', () => {
        expect(analyticsScreen('/notices/id/conditions/result')).toBeNull();
        expect(analyticsScreen('/notices?search=person')).toBeNull();
        expect(analyticsScreen('/guide')).toEqual({
            path: '/guide',
            title: '가이드',
        });
    });
});
