import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { test } from 'node:test';
import {
    internalArtifact,
    scriptHashes,
    securityHeaders,
} from './production-security.mjs';

test('only development reports are excluded; evidence viewers remain available', () => {
    for (const name of [
        'review-public18-20261108.html',
        'jibro-code-model.html',
    ])
        assert.equal(internalArtifact(name), true);
    for (const name of [
        'pdf-viewer.html',
        'evidence-dynamic.html',
        'evidence-extracts.json',
        'images/guide-document-bricks.svg',
    ])
        assert.equal(internalArtifact(name), false);
});

test('script hashes trust exact normalized bytes, not a changed script or external script', () => {
    const expected = `'sha256-${crypto.createHash('sha256').update('\nconst page = 25;\n').digest('base64')}'`;
    const hashes = scriptHashes(
        '<script type="module">\r\nconst page = 25;\r\n</script><script src="/assets/app.js"></script>',
    );
    assert.deepEqual(hashes, [expected]);
    assert.notDeepEqual(
        scriptHashes('<script>const page = 26;</script>'),
        hashes,
    );
    const headers = securityHeaders([...hashes, ...hashes]);
    assert.equal(headers.split(expected).length, 2);
    const scriptPolicy = headers.split('script-src ')[1].split(';')[0];
    assert.doesNotMatch(scriptPolicy, /unsafe-inline|unsafe-eval|https:\s/);
    assert.match(headers, /frame-ancestors 'none'/);
});

test('new executable attributes fail the build, while current official viewers have trusted modules', () => {
    for (const html of [
        '<button onclick="alert(1)">go</button>',
        '<a href="javascript:alert(1)">go</a>',
    ])
        assert.throws(() => scriptHashes(html), /trusted module/);
    for (const name of [
        'pdf-viewer.html',
        'evidence-viewer.html',
        'evidence-dynamic.html',
    ]) {
        const html = fs.readFileSync(
            new URL(`../public/${name}`, import.meta.url),
            'utf8',
        );
        assert.equal(scriptHashes(html).length, 1, name);
    }
});
