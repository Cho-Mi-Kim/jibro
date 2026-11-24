import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import {
    publicPages,
    publicSitePlugin,
    appRouteConfig,
    metadata,
} from './public-site.mjs';

test('Pages documents return to hash routes and keep document navigation under the project base', () => {
    const plugin = publicSitePlugin();
    plugin.configResolved({ mode: 'pages-demo', base: '/jibro/' });
    const assets = {};
    plugin.generateBundle.call({
        emitFile: ({ fileName, source }) => {
            assets[fileName] = source;
        },
    });
    for (const name of ['contact.html', 'privacy.html', '404.html']) {
        const hrefs = [...assets[name].matchAll(/href="([^"]*)"/g)].map(
            (match) => match[1],
        );
        for (const href of [
            '/jibro/#/',
            '/jibro/#/notices',
            '/jibro/contact.html',
            '/jibro/privacy.html',
            '/jibro/#/my#analytics-settings',
        ])
            assert.ok(hrefs.includes(href), `${name}: ${href}`);
        assert.ok(
            hrefs
                .filter((href) => href.startsWith('/'))
                .every((href) => href.startsWith('/jibro/')),
            name,
        );
    }
    assert.match(assets['contact.html'], /href="mailto:support@jibro.live"/);
    // Production keeps ordinary server routes; the demo rewrite must not leak there.
    assert.match(publicPages()['contact.html'], /href="\/notices"/);
    assert.doesNotMatch(publicPages()['contact.html'], /\/jibro\//);
});

test('public documents contain contact details and metadata but no tracking script', () => {
    const pages = publicPages(false);
    assert.match(pages['contact.html'], /support@jibro.live/);
    assert.match(pages['contact.html'], /09:00~18:00/);
    assert.match(pages['contact.html'], /3일 이내/);
    assert.match(pages['privacy.html'], /개인정보 보호 담당/);
    assert.doesNotMatch(pages['privacy.html'], /<h2>선택적 방문 통계/);
    assert.match(publicPages(true)['privacy.html'], /<h2>선택적 방문 통계/);
    for (const page of Object.values(pages))
        assert.doesNotMatch(page, /<script|G-TEST/);
    assert.match(metadata(), /name="description"/);
    assert.match(pages['404.html'], /noindex,follow/);
    assert.match(pages['robots.txt'], /Disallow: \/preparations/);
    assert.match(pages['sitemap.xml'], /https:\/\/jibro.live\/privacy/);
});

test('server route allowlist comes from actual application paths and excludes arbitrary URLs', () => {
    const source = fs.readFileSync(
        new URL('../src/app/App.tsx', import.meta.url),
        'utf8',
    );
    const config = appRouteConfig(source);
    const pattern = config.match(/location ~ (.+) \{/)[1];
    const route = new RegExp(pattern);
    for (const path of [
        '/',
        '/notices',
        '/notices/abc-123',
        '/preparations/id/conditions/result',
        '/login',
    ])
        assert.ok(route.test(path), path);
    for (const path of [
        '/missing-page',
        '/notices/id/extra',
        '/secret.env',
        '/arbitrary/index.html',
        '/development',
    ])
        assert.ok(!route.test(path), path);
});
