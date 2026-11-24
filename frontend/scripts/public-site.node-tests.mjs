import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import { publicPages, appRouteConfig, metadata } from './public-site.mjs';

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
