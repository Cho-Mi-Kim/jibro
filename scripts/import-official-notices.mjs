import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

// Explicit, local-only import of the source comparison recorded alongside this dataset.
// It never publishes personal guidance, overwrites an existing notice, or touches accounts/preparations.
const root = fileURLToPath(new URL('../', import.meta.url));
const dataset = JSON.parse(readFileSync(new URL('../data/notices/official-20261101.json', import.meta.url), 'utf8'));
const container = 'jibro-housing-dev-postgres-1';
const literal = (value) => value == null ? 'NULL' : "'" + String(value).replaceAll("'", "''") + "'";
const validDate = (value) => /^\d{4}-\d{2}-\d{2}$/.test(value) && new Date(value).toISOString().slice(0, 10) === value;
const ids = new Set();
for (const item of dataset.items) {
    assert.match(item.panId, /^\d{16}$/);
    assert.ok(!ids.has(item.panId), 'Duplicate official notice');
    ids.add(item.panId);
    const url = new URL(item.content.evidence.url);
    assert.equal(url.origin, 'https://apply.lh.or.kr');
    assert.equal(url.pathname, '/lhapply/apply/wt/wrtanc/selectWrtancInfo.do');
    assert.equal(url.searchParams.get('panId'), item.panId);
    assert.ok(!url.username && !url.password);
    for (const field of ['publishedDate', 'startDate', 'endDate']) assert.ok(validDate(item[field]));
    assert.ok(item.startDate <= item.endDate);
    assert.equal(item.agency, 'LH');
    assert.ok(item.title.length <= 300 && item.title.length > 0);
    assert.equal(item.content.evidence.checkedAt, dataset.checkedAt);
    assert.equal(item.content.questions.length, 0);
    assert.equal(item.content.documents.length, 0);
    for (const schedule of item.content.schedules) assert.ok(validDate(schedule.date));
}
assert.ok(validDate(dataset.checkedAt));
if (!process.argv.includes('--apply')) {
    console.log(JSON.stringify({ mode: 'validation-only', checkedAt: dataset.checkedAt, notices: dataset.items.map(i => i.title) }, null, 2));
} else {
    const inspected = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
    assert.equal(inspected.Config.Labels['com.docker.compose.project'], 'jibro-housing-dev');
    assert.equal(inspected.Config.Labels['com.docker.compose.service'], 'postgres');
    const sql = (statement) => execFileSync('docker', ['exec', '-i', container, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'jibro', '-d', 'jibro', '-At'], {
        input: statement, encoding: 'utf8', maxBuffer: 5_000_000,
    }).trim();
    const matches = dataset.items.map(i => `content->'evidence'->>'url' LIKE ${literal('%panId=' + i.panId + '%')}`).join(' OR ');
    mkdirSync(resolve(root, '.tools/notice-imports'), { recursive: true });
    const stamp = new Date().toISOString().replaceAll(':', '-');
    // Public notice rows only; no user or authentication data is exported.
    writeFileSync(resolve(root, `.tools/notice-imports/${stamp}-before.json`), sql(`SELECT coalesce(json_agg(n),'[]') FROM notice n WHERE ${matches};`) + '\n');
    const statements = dataset.items.map(item => {
        // Same UUID v3 algorithm used by Java UUID.nameUUIDFromBytes in LhNoticeClient.
        const bytes = createHash('md5').update(item.content.evidence.url).digest();
        bytes[6] = (bytes[6] & 15) | 48;
        bytes[8] = (bytes[8] & 63) | 128;
        const hex = bytes.toString('hex');
        const key = 'LH:' + [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-');
        const reason = `공식 기본 정보 대조 ${dataset.checkedAt} / Codex 보조 / 개인별 안내는 검수 대기. data/notices/official-20261101.json`;
        const values = [randomUUID(),key,item.title,item.agency,item.region,item.type,item.startDate,item.endDate,item.publishedDate,'PASS','HOLD',reason,JSON.stringify(item.content)].map(literal).join(',');
        return `INSERT INTO notice(id,source_key,title,agency,region,support_type,start_date,end_date,published_date,basic_review,guide_review,reason,content)
            SELECT ${values} WHERE NOT EXISTS (SELECT 1 FROM notice WHERE source_key=${literal(key)} OR content->'evidence'->>'url' LIKE ${literal('%panId=' + item.panId + '%')});`;
    });
    sql('BEGIN; LOCK TABLE notice IN SHARE ROW EXCLUSIVE MODE;\n' + statements.join('\n') + '\nCOMMIT;');
    const result = JSON.parse(sql(`SELECT coalesce(json_agg(r),'[]') FROM (SELECT id,title,basic_review,guide_review FROM notice WHERE ${matches}) r;`));
    assert.equal(result.length, dataset.items.length, 'Expected exactly one row per official notice');
    writeFileSync(resolve(root, `.tools/notice-imports/${stamp}-result.json`), JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify({ mode: 'local-import', notices: result, note: 'Existing rows are preserved. Guide content is not approved.' }, null, 2));
}
