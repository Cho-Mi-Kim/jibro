const consentKey = 'jibro:analytics-consent:v1';
const preferenceEvent = 'jibro:analytics-preference';
export const analyticsSettingsEvent = 'jibro:analytics-settings';
const consentLifetime = 180 * 24 * 60 * 60 * 1000;
export type AnalyticsConsentValue = 'granted' | 'denied';
let temporaryConsent: AnalyticsConsentValue | null = null;

export function readAnalyticsConsent(): AnalyticsConsentValue | null {
    if (temporaryConsent) return temporaryConsent;
    try {
        const item: unknown = JSON.parse(
            localStorage.getItem(consentKey) ?? 'null',
        );
        if (
            !item ||
            typeof item !== 'object' ||
            !('value' in item) ||
            !('at' in item)
        )
            return null;
        if (
            (item.value !== 'granted' && item.value !== 'denied') ||
            typeof item.at !== 'number' ||
            item.at > Date.now() ||
            Date.now() - item.at >= consentLifetime
        )
            return null;
        return item.value;
    } catch {
        return null;
    }
}

export function saveAnalyticsConsent(value: AnalyticsConsentValue) {
    try {
        localStorage.setItem(
            consentKey,
            JSON.stringify({ value, at: Date.now() }),
        );
        temporaryConsent = null;
    } catch {
        temporaryConsent = value;
    }
    window.dispatchEvent(new CustomEvent(preferenceEvent, { detail: value }));
}

export function subscribeAnalyticsConsent(listener: () => void) {
    window.addEventListener(preferenceEvent, listener);
    window.addEventListener('storage', listener);
    return () => {
        window.removeEventListener(preferenceEvent, listener);
        window.removeEventListener('storage', listener);
    };
}

// Only allow public screen categories. Never send route identifiers or search parameters.
export function analyticsScreen(
    pathname: string,
): { path: string; title: string } | null {
    if (pathname === '/') return { path: '/', title: '홈' };
    if (pathname === '/notices')
        return { path: '/notices', title: '공고 목록' };
    if (/^\/notices\/[A-Za-z0-9-]+$/.test(pathname))
        return { path: '/notices/detail', title: '공고 상세' };
    if (pathname === '/guide') return { path: '/guide', title: '가이드' };
    if (pathname === '/compare')
        return { path: '/compare', title: '공고 비교' };
    return null;
}

type AnalyticsWindow = Window & {
    dataLayer?: unknown[];
    gtag?: (...args: unknown[]) => void;
    [key: `ga-disable-${string}`]: boolean;
};
export class AnalyticsClient {
    private initialized = false;
    private previous: string | null = null;
    private readonly id: string;
    private readonly origin: string;
    private readonly tagId: string;
    constructor(id: string, origin: string, tagId: string) {
        this.id = id;
        this.origin = origin;
        this.tagId = tagId;
    }

    get enabled() {
        return (
            /^G-[A-Z0-9]+$/.test(this.id) &&
            /^GTM-[A-Z0-9]+$/.test(this.tagId) &&
            this.origin === 'https://jibro.live'
        );
    }

    update(consent: AnalyticsConsentValue | null, pathname: string) {
        if (!this.enabled) return;
        const target = window as unknown as AnalyticsWindow;
        const screen = analyticsScreen(pathname);
        const allowed = consent === 'granted' && !!screen;
        target[`ga-disable-${this.id}`] = !allowed;
        if (!allowed) {
            this.previous = null;
            if (consent !== 'granted' && this.initialized)
                this.command('consent', 'update', {
                    analytics_storage: 'denied',
                });
            if (consent !== 'granted') this.clearCookies();
            return;
        }
        if (!this.initialized) {
            this.command('consent', 'default', {
                analytics_storage: 'denied',
                ad_storage: 'denied',
                ad_user_data: 'denied',
                ad_personalization: 'denied',
            });
        }
        // Apply the visitor's actual choice before initializing the destination.
        this.command('consent', 'update', {
            analytics_storage: 'granted',
            ad_storage: 'denied',
            ad_user_data: 'denied',
            ad_personalization: 'denied',
        });
        if (!this.initialized) {
            this.initialized = true;
            // New Google tags use their issued container; set privacy parameters
            // before the js command starts destinations configured in that tag.
            this.command('set', {
                send_page_view: false,
                allow_google_signals: false,
                allow_ad_personalization_signals: false,
                cookie_expires: 30 * 24 * 60 * 60,
                cookie_update: false,
                page_location: this.origin + screen.path,
                page_title: screen.title,
                page_referrer: '',
            });
            this.command('js', new Date());
            const script = document.createElement('script');
            script.async = true;
            script.id = 'jibro-analytics';
            script.src = `https://www.googletagmanager.com/gtm.js?id=${this.tagId}`;
            script.referrerPolicy = 'no-referrer';
            script.dataset.loadState = 'loading';
            script.addEventListener('load', () => {
                script.dataset.loadState = 'loaded';
            });
            script.addEventListener('error', () => {
                script.dataset.loadState = 'failed';
            });
            document.head.append(script);
        }
        if (this.previous !== screen.path) {
            this.command('event', 'page_view', {
                send_to: this.id,
                page_location: this.origin + screen.path,
                page_title: screen.title,
                page_referrer: '',
            });
            this.previous = screen.path;
        }
    }

    private command(...args: unknown[]) {
        const target = window as unknown as AnalyticsWindow;
        target.gtag ??= function (...commands: unknown[]) {
            // Keep the standard global helper and Google's Arguments queue format.
            void commands;
            // eslint-disable-next-line prefer-rest-params -- Google's gtag queue uses Arguments entries.
            (target.dataLayer ??= []).push(arguments);
        };
        target.gtag(...args);
    }

    private clearCookies() {
        for (const cookie of document.cookie.split(';')) {
            const name = cookie.trim().split('=')[0];
            if (name === '_ga' || name.startsWith('_ga_')) {
                for (const domain of [
                    '',
                    '; domain=jibro.live',
                    '; domain=.jibro.live',
                ])
                    document.cookie = `${name}=; path=/; max-age=0; SameSite=Lax${domain}`;
            }
        }
    }
}
