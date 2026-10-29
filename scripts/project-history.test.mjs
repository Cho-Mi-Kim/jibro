import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import { captureStaged, seoulDate } from './project-history.mjs';

const sourceRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const fixtures = path.resolve(sourceRoot, '.tools/history-tests');
fs.mkdirSync(fixtures, { recursive: true });

function git(root, ...args) {
    return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function fixture(t) {
    const root = fs.mkdtempSync(path.join(fixtures, 'case-'));
    assert.ok(path.resolve(root).startsWith(fixtures + path.sep));
    t.after(() => {
        if (!path.resolve(root).startsWith(fixtures + path.sep)) throw new Error('Invalid cleanup target');
        fs.rmSync(root, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
    });
    git(root, 'init', '--initial-branch=fixture');
    git(root, 'config', 'user.name', 'History Test');
    git(root, 'config', 'user.email', 'history@example.invalid');
    git(root, 'config', 'commit.gpgsign', 'false');
    fs.mkdirSync(path.join(root, 'docs/history/entries'), { recursive: true });
    fs.mkdirSync(path.join(root, 'scripts'));
    fs.mkdirSync(path.join(root, '.githooks'));
    fs.copyFileSync(path.join(sourceRoot, 'scripts/project-history.mjs'), path.join(root, 'scripts/project-history.mjs'));
    fs.copyFileSync(path.join(sourceRoot, '.githooks/pre-commit'), path.join(root, '.githooks/pre-commit'));
    fs.writeFileSync(path.join(root, 'docs/history/README.md'), '# Fixture\n\nmanual introduction\n\n<!-- PROJECT-HISTORY:START -->\nempty\n<!-- PROJECT-HISTORY:END -->\n\nmanual footer\n');
    git(root, 'add', '.');
    git(root, 'commit', '-m', 'fixture baseline');
    return root;
}

test('한국 시간의 날짜 경계를 사용한다', () => {
    assert.equal(seoulDate(new Date('2026-10-28T15:01:00Z')), '2026-10-29');
});

test('stage된 변경이 없으면 파일을 생성하지 않는다', (t) => {
    const root = fixture(t);
    assert.equal(captureStaged(root).skipped, true);
    assert.equal(fs.readdirSync(path.join(root, 'docs/history/entries')).length, 0);
});

test('실제 stage된 증감만 수집하고 원본의 미선택 수정은 stage하지 않으며 중복 생성하지 않는다', (t) => {
    const root = fixture(t);
    const filename = '구현 메모.txt';
    fs.writeFileSync(path.join(root, filename), 'selected\n');
    git(root, 'add', '--', filename);
    fs.writeFileSync(path.join(root, filename), 'selected\nunselected\n');
    const result = captureStaged(root);
    assert.ok(result.generated);
    const text = git(root, 'show', `:${result.generated}`);
    assert.match(text, /맥락 보완 필요/);
    assert.match(text, /구현 메모\.txt/);
    assert.match(text, /\+1 \/ -0/);
    assert.equal(git(root, 'show', `:${filename}`), 'selected\n');
    assert.match(fs.readFileSync(path.join(root, 'docs/history/README.md'), 'utf8'), /manual footer/);
    assert.equal(captureStaged(root).generated, null);
    assert.equal(fs.readdirSync(path.join(root, 'docs/history/entries')).length, 1);
});

test('상세 기록을 함께 stage했으면 자동 초안을 중복 생성하지 않는다', (t) => {
    const root = fixture(t);
    const record = 'docs/history/entries/2026-10-29-human.md';
    fs.writeFileSync(path.join(root, 'change.txt'), 'change\n');
    fs.writeFileSync(path.join(root, record), '# 상세 결정 기록\n\n- 기록일: 2026-10-29 (Asia/Seoul)\n- 기록 상태: 근거 정리·담당자 검토 전\n\n실제 판단 근거를 담당자가 작성했습니다.\n');
    git(root, 'add', '.');
    const result = captureStaged(root);
    assert.equal(result.generated, null);
    assert.equal(result.detailedRecordStaged, true);
    assert.match(git(root, 'show', ':docs/history/README.md'), /상세 결정 기록/);
    assert.match(git(root, 'show', `:${record}`), /실제 판단 근거/);
});

test('기록 목록에 stage하지 않은 수정이 있으면 보존하고 새 기록도 만들지 않는다', (t) => {
    const root = fixture(t);
    fs.writeFileSync(path.join(root, 'change.txt'), 'change\n');
    git(root, 'add', 'change.txt');
    const filename = path.join(root, 'docs/history/README.md');
    fs.appendFileSync(filename, '\nuser unselected note\n');
    const before = fs.readFileSync(filename, 'utf8');
    assert.throws(() => captureStaged(root), /stage하지 않은 수정/);
    assert.equal(fs.readFileSync(filename, 'utf8'), before);
    assert.equal(fs.readdirSync(path.join(root, 'docs/history/entries')).length, 0);
    assert.equal(git(root, 'diff', '--cached', '--name-only').trim(), 'change.txt');
});

test('실제 git commit hook이 기록을 포함하고 커밋을 재귀 생성하지 않는다', (t) => {
    const root = fixture(t);
    execFileSync(process.execPath, ['scripts/project-history.mjs', 'install'], { cwd: root, stdio: 'pipe' });
    fs.writeFileSync(path.join(root, 'change.txt'), 'change\n');
    git(root, 'add', 'change.txt');
    git(root, 'commit', '-m', 'verify actual hook');
    assert.equal(git(root, 'rev-list', '--count', 'HEAD').trim(), '2');
    assert.match(git(root, 'show', '--format=', '--name-only', 'HEAD'), /docs\/history\/entries\/.+-auto-.+\.md/);
    assert.equal(git(root, 'status', '--porcelain').trim(), '');
});

test('설치 명령이 기존 hook 설정을 덮어쓰지 않는다', (t) => {
    const root = fixture(t);
    git(root, 'config', '--local', 'core.hooksPath', 'custom-hooks');
    const result = spawnSync(process.execPath, ['scripts/project-history.mjs', 'install'], { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.equal(git(root, 'config', '--get', 'core.hooksPath').trim(), 'custom-hooks');
});
