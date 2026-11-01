import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

// Versioned local editorial import. No user is impersonated as an administrator.
const root = fileURLToPath(new URL('../', import.meta.url));
const read = name => JSON.parse(readFileSync(resolve(root, 'data/notices', name), 'utf8'));
const questionUpgrade = process.argv.includes('--question-options');
const review = read(questionUpgrade ? 'question-options-20261101.json' : 'guidance-review-20261101.json');
const original = read(questionUpgrade ? 'guidance-review-20261101.json' : 'official-20261101.json');
const literal = value => "'" + String(value).replaceAll("'", "''") + "'";
const official = value => {
    const url = new URL(value);
    assert.equal(url.origin, 'https://apply.lh.or.kr');
    assert.ok(!url.username && !url.password);
};
assert.equal(new Set(review.items.map(i => i.panId)).size, review.items.length);
for (const item of review.items) {
    assert.ok(original.items.some(i => i.panId === item.panId));
    assert.equal(item.basic, 'PASS');
    assert.ok(['PASS', 'HOLD'].includes(item.guide));
    const c = item.content;
    assert.equal(new URL(c.evidence.url).searchParams.get('panId'), item.panId);
    const questionIds = new Set(c.questions.map(q => q.id));
    for (const list of [c.questions, c.documents, c.schedules]) {
        assert.equal(new Set(list.map(v => v.id)).size, list.length);
    }
    for (const e of [c.evidence, ...c.attachments, ...c.questions.map(q => q.evidence), ...c.documents.map(d => d.evidence), ...c.schedules.map(s => s.evidence)]) {
        official(e.url);
        assert.equal(e.checkedAt, review.checkedAt);
    }
    for (const d of c.documents) {
        assert.ok(d.conditionQuestionId === null || questionIds.has(d.conditionQuestionId));
        if (d.issueUrl) official(d.issueUrl);
    }
    if (item.guide === 'PASS') assert.ok(c.questions.length && c.documents.length && c.schedules.length);
}
if (!process.argv.includes('--apply')) {
    console.log(`Validated ${review.items.length} source-backed review decisions; no database changes.`);
} else {
    const container = 'jibro-housing-dev-postgres-1';
    const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
    assert.equal(info.Config.Labels['com.docker.compose.project'], 'jibro-housing-dev');
    assert.equal(info.Config.Labels['com.docker.compose.service'], 'postgres');
    const sql = statement => execFileSync('docker', ['exec', '-i', container, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'jibro', '-d', 'jibro', '-At'], {
        input: statement, encoding: 'utf8', maxBuffer: 5_000_000,
    }).trim();
    const matches = review.items.map(i => `content->'evidence'->>'url' = ${literal(i.content.evidence.url)}`).join(' OR ');
    const before = sql(`SELECT coalesce(json_agg(n),'[]') FROM notice n WHERE ${matches};`);
    assert.equal(JSON.parse(before).length, review.items.length, 'Import the official catalog first; ambiguous matches are not allowed.');
    const folder = resolve(root, '.tools/notice-imports');
    mkdirSync(folder, { recursive: true });
    const stamp = new Date().toISOString().replaceAll(':', '-');
    writeFileSync(resolve(folder, `${stamp}-guidance-before.json`), before + '\n');
    const statements = review.items.map(item => {
        const old = original.items.find(i => i.panId === item.panId);
        const content = literal(JSON.stringify(item.content));
        const match = `content->'evidence'->>'url' = ${literal(item.content.evidence.url)}`;
        const applied = `${match} AND content=${content}::jsonb AND version=${item.expectedVersion + 1} AND revision=${item.expectedRevision + 1} AND basic_review='PASS' AND guide_review=${literal(item.guide)} AND reason=${literal(item.reason)} AND NOT change_review`;
        return `DO $review$ DECLARE affected integer; BEGIN
            IF EXISTS(SELECT 1 FROM notice WHERE ${applied}) THEN RETURN; END IF;
            UPDATE notice SET content=${content}::jsonb, basic_review='PASS', guide_review=${literal(item.guide)}, reason=${literal(item.reason)}, version=version+1, revision=revision+1, change_review=false, updated_at=now()
            WHERE ${match} AND version=${item.expectedVersion} AND revision=${item.expectedRevision}
              AND basic_review='PASS' AND guide_review=${literal(questionUpgrade ? old.guide : 'HOLD')} AND NOT change_review
              AND content=${literal(JSON.stringify(old.content))}::jsonb;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN RAISE EXCEPTION 'Review conflict for ${item.panId}; no changes committed'; END IF;
        END $review$;`;
    });
    // Lock before checking/updating all three rows; a conflict rolls back the whole batch.
    sql('BEGIN; LOCK TABLE notice IN SHARE ROW EXCLUSIVE MODE;\n' + statements.join('\n') + '\nCOMMIT;');
    const result = sql(`SELECT json_agg(n) FROM (SELECT id,title,version,revision,basic_review,guide_review,reason FROM notice WHERE ${matches}) n;`);
    writeFileSync(resolve(folder, `${stamp}-guidance-result.json`), result + '\n');
    console.log(result);
}
