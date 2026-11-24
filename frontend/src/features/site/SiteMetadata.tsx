import { useLayoutEffect } from 'react';
import { useLocation } from 'react-router';

const homeTitle = document.title;
const publicTitles: Record<string, string> = {
    '/': homeTitle,
    '/notices': '공고 찾기 · 집으로',
    '/guide': '주거 준비 가이드 · 집으로',
};

export function SiteMetadata() {
    const { pathname } = useLocation();
    useLayoutEffect(() => {
        const notice = /^\/notices\/[A-Za-z0-9-]+$/.test(pathname);
        const indexable = pathname in publicTitles || notice;
        document.title =
            publicTitles[pathname] ??
            (notice ? '공고 상세 · 집으로' : '집으로');
        let robots = document.querySelector<HTMLMetaElement>(
            'meta[name="robots"]',
        );
        if (!robots) {
            robots = document.createElement('meta');
            robots.name = 'robots';
            document.head.append(robots);
        }
        robots.content =
            indexable && import.meta.env.MODE !== 'pages-demo'
                ? 'index,follow'
                : 'noindex,follow';
        const canonical = document.querySelector<HTMLLinkElement>(
            'link[rel="canonical"]',
        );
        if (canonical)
            canonical.href =
                'https://jibro.live' + (indexable ? pathname : '/');
    }, [pathname]);
    return null;
}
