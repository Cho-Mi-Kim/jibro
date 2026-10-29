import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Explicit local sample verification. Creates a synthetic account and persistent record.
const base = process.env.VERIFY_BASE_URL ?? 'http://127.0.0.1:8080';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname), 'Local host required');
const cookies = new Map();
let access;
async function request(path, method = 'GET', body, expected = 200) {
    const headers = { Accept: 'application/json' };
    if (method !== 'GET') {
        const csrf = await request('/auth/csrf');
        headers['X-CSRF-TOKEN'] = csrf.token;
    }
    if (cookies.size) headers.Cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    if (access && !path.startsWith('/auth/')) headers.Authorization = `Bearer ${access}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(base + '/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    for (const value of response.headers.getSetCookie()) {
        const [pair] = value.split(';');
        const index = pair.indexOf('=');
        cookies.set(pair.slice(0, index), pair.slice(index + 1));
    }
    assert.equal(response.status, expected, `${method} ${path} status`);
    const text = await response.text();
    return text ? JSON.parse(text) : undefined;
}
assert.equal((await request('/meta')).sampleData, true, 'Run only against explicit sample data');
const email = `api-verify-${randomUUID()}@example.invalid`;
const password = randomUUID() + '-test';
await request('/auth/signup', 'POST', { name: 'API 검증', email, password }, 201);
const session = await request('/auth/login', 'POST', { email, password });
access = session.accessToken;
assert.equal((await request('/users/me')).id, session.user.id);
const page = await request('/notices?region=' + encodeURIComponent('서울') + '&type=' + encodeURIComponent('공공임대'));
const notice = page.items.find((n) => n.canStart && n.title.startsWith('[로컬 샘플]'));
assert.ok(notice, 'A reviewed local sample is required');
let p = await request('/preparations', 'POST', { noticeId: notice.id, version: notice.version });
const duplicate = await request('/preparations', 'POST', { noticeId: notice.id, version: notice.version });
assert.equal(duplicate.id, p.id);
p = await request(`/preparations/${p.id}/answers`, 'PUT', { revision: p.revision, answers: { residence: 'YES', house: 'NO' } });
p = await request(`/preparations/${p.id}/documents/${p.documents[0].id}`, 'PATCH', { revision: p.revision, state: 'READY', note: '실제 API 저장 검증' });
p = await request(`/preparations/${p.id}/schedules/apply`, 'PATCH', { revision: p.revision, recorded: true });
assert.equal(p.progress, 100);
assert.equal(p.houseStage, 5);
await request(`/preparations/${p.id}/answers`, 'PUT', { revision: 1, answers: {} }, 409);
await request('/admin/reviews', 'GET', undefined, 403);
access = undefined;
const refresh = await request('/auth/refresh', 'POST');
access = refresh.accessToken;
const restored = await request(`/preparations/${p.id}`);
assert.equal(restored.progress, 100);
assert.equal(restored.documents[0].note, '실제 API 저장 검증');
await request('/auth/logout', 'POST', undefined, 204);
access = undefined;
await request('/auth/refresh', 'POST', undefined, 401);
console.log('PASS: real HTTP signup/login, search, idempotent start, answers, documents, schedule, 100% progress, conflicts, role guard, refresh restore, logout.');
console.log('A synthetic @example.invalid account and preparation were retained in the local sample database.');
