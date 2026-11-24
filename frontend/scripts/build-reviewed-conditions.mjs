import { readdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { format, resolveConfig } from 'prettier';

const directory = fileURLToPath(
    new URL('../src/features/housing/reviewedConditions/', import.meta.url),
);
const groups = new Map();
const index = {};
const check = process.argv.includes('--check');
const reviewDirectory = fileURLToPath(
    new URL('../../data/notices/condition-review-20261121/', import.meta.url),
);
const answerContractFields = [
    'inputKind',
    'options',
    'constraints',
    'answerDependencies',
    'applicability',
];
const answerContracts = new Map();
async function readJson(file) {
    return JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
}
function rememberContract(noticeId, question, complete = false) {
    const key = `${noticeId}:${question.id}`;
    const contract = complete ? {} : (answerContracts.get(key) ?? {});
    for (const field of answerContractFields) {
        if (complete || Object.hasOwn(question, field))
            contract[field] = question[field] ?? null;
    }
    if (Object.keys(contract).length) answerContracts.set(key, contract);
}
// Keep published answer semantics in the reviewed source data. The catalog
// only copies the guard; builds never depend on local audit snapshots or a DB.
for (const file of (await readdir(reviewDirectory))
    .filter((name) => /^batch-[abc]\.json$/.test(name))
    .sort()) {
    for (const patch of await readJson(path.join(reviewDirectory, file))) {
        for (const question of patch.questionPatches ?? [])
            rememberContract(patch.noticeId, question);
    }
}
for (const file of (await readdir(path.join(reviewDirectory, 'full-review')))
    .filter((name) => name.endsWith('.json'))
    .sort()) {
    const review = await readJson(
        path.join(reviewDirectory, 'full-review', file),
    );
    for (const question of review.content.questions)
        rememberContract(review.noticeId, question, true);
}
for (const file of (await readdir(directory))
    .filter((name) => /^batch-[abc]\.json$/.test(name))
    .sort()) {
    const { entries } = await readJson(path.join(directory, file));
    if (!Array.isArray(entries)) throw new Error(`Invalid entries: ${file}`);
    for (const entry of entries) {
        if (
            !/^[0-9a-f-]{36}$/.test(entry.noticeId) ||
            !entry.questionId ||
            !entry.heading ||
            !entry.preview ||
            !entry.sourceReason ||
            !entry.sourceEvidence?.url ||
            !Array.isArray(entry.sections)
        )
            throw new Error(`Incomplete entry ${file}:${entry.questionId}`);
        const answerContract = {
            ...(Object.hasOwn(entry, 'sourceOptions')
                ? { options: entry.sourceOptions }
                : {}),
            ...answerContracts.get(`${entry.noticeId}:${entry.questionId}`),
        };
        const rows = groups.get(entry.noticeId) ?? [];
        rows.push({
            ...entry,
            ...(Object.keys(answerContract).length ? { answerContract } : {}),
        });
        groups.set(entry.noticeId, rows);
        const source = entry.sourceEvidence.url.split('#')[0];
        index[source] ??= [];
        if (!index[source].includes(entry.noticeId))
            index[source].push(entry.noticeId);
    }
}
async function output(name, value) {
    const target = path.join(directory, name);
    if (check) {
        const actual = JSON.parse(await readFile(target, 'utf8'));
        if (JSON.stringify(actual) !== JSON.stringify(value))
            throw new Error(`Stale reviewed conditions: ${name}`);
    } else {
        await writeFile(
            target,
            await format(JSON.stringify(value), {
                ...(await resolveConfig(target)),
                filepath: target,
            }),
        );
    }
}
for (const [id, entries] of groups)
    await output(`notice-${id}.json`, { entries });
await output('index.json', index);
for (const name of await readdir(directory)) {
    const id = /^notice-([0-9a-f-]{36})\.json$/.exec(name)?.[1];
    if (id && !groups.has(id))
        throw new Error(`Orphan reviewed conditions: ${name}`);
}
console.log(
    `${check ? 'Checked' : 'Built'} ${groups.size} notice catalogs; index has ${Object.keys(index).length} official sources`,
);
