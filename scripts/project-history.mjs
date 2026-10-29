import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const HISTORY = 'docs/history/';
const INDEX = `${HISTORY}README.md`;
const ENTRIES = `${HISTORY}entries/`;
const START = '<!-- PROJECT-HISTORY:START -->';
const END = '<!-- PROJECT-HISTORY:END -->';

function git(root, args, optional = false) {
    try {
        return execFileSync('git', args, {
            cwd: root,
            encoding: 'utf8',
            maxBuffer: 16 * 1024 * 1024,
            stdio: ['ignore', 'pipe', 'pipe'],
        });
    } catch (error) {
        if (optional) return null;
        throw new Error(`git ${args[0]} 실패: ${error.stderr?.toString().trim() || error.message}`);
    }
}

const normalize = (text) => text.replace(/\r\n/g, '\n');
const escape = (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/\|/g, '&#124;').replace(/\[/g, '&#91;').replace(/\]/g, '&#93;')
    .replace(/[\r\n]/g, ' ');

export function seoulDate(date = new Date()) {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(date);
    const value = (type) => parts.find((part) => part.type === type).value;
    return `${value('year')}-${value('month')}-${value('day')}`;
}

function stagedChanges(root) {
    const values = git(root, ['diff', '--cached', '--no-renames', '--name-status', '-z']).split('\0');
    const stats = new Map(git(root, ['diff', '--cached', '--no-renames', '--numstat', '-z'])
        .split('\0').filter(Boolean).map((line) => {
            const first = line.indexOf('\t');
            const second = line.indexOf('\t', first + 1);
            return [line.slice(second + 1), { added: line.slice(0, first), removed: line.slice(first + 1, second) }];
        }));
    const changes = [];
    for (let index = 0; index < values.length - 1; index += 2) {
        changes.push({ status: values[index], file: values[index + 1], ...stats.get(values[index + 1]) });
    }
    return changes;
}

function parseEntry(file, text) {
    return {
        file,
        title: text.match(/^#\s+(.+)$/m)?.[1] || path.basename(file, '.md'),
        date: text.match(/^- 기록일:\s*(\d{4}-\d{2}-\d{2})/m)?.[1] || '확인 필요',
        status: text.match(/^- 기록 상태:\s*(.+)$/m)?.[1] || '확인 필요',
    };
}

function indexedEntries(root) {
    return git(root, ['ls-files', '--cached', '-z', '--', ENTRIES]).split('\0')
        .filter((file) => file.endsWith('.md'))
        .map((file) => parseEntry(file, git(root, ['show', `:${file}`])));
}

function workingEntries(root) {
    const directory = path.join(root, ENTRIES);
    if (!fs.existsSync(directory)) return [];
    return fs.readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isFile() && entry.name.endsWith('.md'))
        .map((entry) => {
            const file = ENTRIES + entry.name;
            return parseEntry(file, fs.readFileSync(path.join(root, file), 'utf8'));
        });
}

function renderIndex(text, entries) {
    const start = text.indexOf(START);
    const end = text.indexOf(END);
    if (start < 0 || end < start || text.indexOf(START, start + START.length) >= 0) {
        throw new Error('기록 목록의 시작·종료 표시를 확인해 주세요.');
    }
    const sorted = [...entries].sort((a, b) => b.date.localeCompare(a.date) || a.file.localeCompare(b.file));
    const rows = sorted.map((entry) => `| ${escape(entry.date)} | [${escape(entry.title)}](entries/${encodeURIComponent(path.basename(entry.file))}) | ${escape(entry.status)} |`);
    const content = rows.length
        ? ['| 기록일 | 내용 | 기록 상태 |', '| --- | --- | --- |', ...rows].join('\n')
        : '아직 등록된 기록이 없습니다.';
    return text.slice(0, start + START.length) + '\n' + content + '\n' + text.slice(end);
}

function record(root, title, changes, automatic) {
    const date = seoulDate();
    const branch = git(root, ['branch', '--show-current']).trim() || 'detached HEAD';
    const parent = git(root, ['rev-parse', '--verify', 'HEAD'], true)?.trim() || '초기 커밋 전';
    const sections = [
        ['배경·문제', '보완 필요: 실제 사용자 문제·기술 제약·발생 현상을 적습니다.'],
        ['고려한 선택지', '보완 필요: 실제 검토한 선택지만 적습니다. Git으로 추정하지 않습니다.'],
        ['결정·이유', '보완 필요: 선택과 판단 근거를 당시 담당자가 확인합니다.'],
        ['변경·해결 과정', automatic
            ? '아래 변경 파일은 커밋 직전 stage된 상태에서 수집했습니다. 구체적인 해결 과정은 보완이 필요합니다.'
            : '작성 필요: 구현·조사·수정한 내용과 관련 파일을 설명합니다.'],
        ['검증·결과', '미확인: 이 도구는 빌드·테스트·배포를 실행하거나 성공 여부를 추정하지 않습니다. 실제 명령과 결과를 적습니다.'],
        ['기여·한계', '보완 필요: 실제 구현 담당·검토자·AI 보조 범위는 Git 작성자만으로 확정하지 않습니다.'],
        ['발표·포트폴리오 메모', '보완 후 작성: 문제 → 선택 이유 → 실행 → 확인된 결과 순서로 정리합니다.'],
        ['후속 작업', '- 당시 담당자가 판단 근거·검증 결과·역할과 한계를 확인합니다.'],
    ];
    const rows = changes.slice(0, 100).map((change) => {
        const numbers = change.added === '-' ? '바이너리' : `+${change.added || 0} / -${change.removed || 0}`;
        return `| ${escape(change.status)} | <code>${escape(change.file)}</code> | ${numbers} |`;
    });
    const files = rows.length
        ? `총 ${changes.length}개. 이름 변경은 삭제·추가로 표시합니다.\n\n| 상태 | 저장소 기준 파일 | 변경 줄 |\n| --- | --- | --- |\n${rows.join('\n')}${changes.length > 100 ? '\n\n100개 이후 목록은 Git diff에서 확인합니다.' : ''}`
        : '기록 생성 시 stage된 변경 파일이 없습니다. 실제 변경 파일을 보완합니다.';
    return `# ${title}\n\n- 기록일: ${date} (Asia/Seoul)\n- 기록 상태: ${automatic ? '맥락 보완 필요' : '작성 중'}\n- 작업 상태: 확인 필요\n- 유형: ${automatic ? 'Git 자동 수집' : '작업 기록'}\n- 담당·검토: 미확인\n- 관련 이슈·PR·커밋: 미등록\n- 수집 당시 브랜치: \`${branch}\`\n- 기준 커밋: \`${parent}\` (이 기록 생성 전 HEAD이며 새 커밋의 해시가 아님)\n\n${sections.map(([heading, content]) => `## ${heading}\n\n${content}\n`).join('\n')}\n## 변경 파일\n\n${files}\n`;
}

export function syncIndex(root) {
    const filename = path.join(root, INDEX);
    const before = normalize(fs.readFileSync(filename, 'utf8'));
    const after = renderIndex(before, workingEntries(root));
    if (after !== before) fs.writeFileSync(filename, after);
    return workingEntries(root).length;
}

export function captureStaged(root) {
    const changes = stagedChanges(root);
    if (!changes.length) return { skipped: true, reason: 'stage된 변경 없음' };
    const meaningful = changes.filter((change) => !change.file.startsWith(HISTORY));
    const stagedRecords = changes.filter((change) => change.file.startsWith(ENTRIES) && change.file.endsWith('.md') && change.status !== 'D');
    const entries = indexedEntries(root);
    const indexPath = path.join(root, INDEX);
    const indexedReadme = git(root, ['show', `:${INDEX}`], true);
    if (indexedReadme === null || !fs.existsSync(indexPath)) {
        throw new Error('docs/history/README.md를 먼저 stage해 주세요. 기존 선택을 대신 stage하지 않습니다.');
    }
    const before = normalize(fs.readFileSync(indexPath, 'utf8'));
    if (before !== normalize(indexedReadme)) {
        throw new Error('기록 목록에 stage하지 않은 수정이 있어 자동 갱신을 생략합니다. 변경을 보존하고 기록을 수동 보완해 주세요.');
    }
    let generated = null;
    let generatedText = null;
    if (meaningful.length && !stagedRecords.length) {
        const digest = crypto.createHash('sha256').update(git(root, ['diff', '--cached', '--raw', '--no-renames', '-z'])).digest('hex').slice(0, 10);
        generated = `${ENTRIES}${seoulDate()}-auto-${digest}.md`;
        if (fs.existsSync(path.join(root, generated))) throw new Error(`자동 기록 대상이 이미 존재합니다: ${generated}`);
        generatedText = record(root, `변경 파일 자동 수집 (${meaningful.length}개)`, meaningful, true);
        entries.push(parseEntry(generated, generatedText));
    }
    const after = renderIndex(before, entries);
    const stagedPaths = [];
    if (generated) {
        fs.mkdirSync(path.join(root, ENTRIES), { recursive: true });
        fs.writeFileSync(path.join(root, generated), generatedText, { flag: 'wx' });
        stagedPaths.push(generated);
    }
    if (after !== before) {
        fs.writeFileSync(indexPath, after);
        stagedPaths.push(INDEX);
    }
    if (stagedPaths.length) git(root, ['add', '--', ...stagedPaths]);
    return { generated, detailedRecordStaged: stagedRecords.length > 0, stagedByTool: stagedPaths };
}

function install(root) {
    const current = git(root, ['config', '--get', 'core.hooksPath'], true)?.trim();
    if (current && path.resolve(root, current) !== path.join(root, '.githooks')) {
        throw new Error(`기존 core.hooksPath가 있어 변경하지 않았습니다: ${current}`);
    }
    fs.chmodSync(path.join(root, '.githooks/pre-commit'), 0o755);
    git(root, ['config', '--local', 'core.hooksPath', '.githooks']);
    console.log('이 저장소에만 프로젝트 기록 hook을 설치했습니다. 자동 커밋·push는 하지 않습니다.');
}

function create(root, args) {
    const value = (name) => {
        const index = args.indexOf(name);
        return index >= 0 ? args[index + 1] : null;
    };
    const slug = value('--slug');
    const title = value('--title');
    if (!slug || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug) || !title || /[\r\n]/.test(title)) {
        throw new Error('new --slug 영문-주제 --title "기록 제목" 형식으로 실행해 주세요.');
    }
    const filename = `${ENTRIES}${seoulDate()}-${slug}.md`;
    fs.mkdirSync(path.join(root, ENTRIES), { recursive: true });
    fs.writeFileSync(path.join(root, filename), record(root, title, stagedChanges(root), false), { flag: 'wx' });
    syncIndex(root);
    console.log(`초안 생성: ${filename}. 실제 배경·판단·검증·기여를 보완해 주세요.`);
}

function main() {
    const root = git(process.cwd(), ['rev-parse', '--show-toplevel']).trim();
    const [command, ...args] = process.argv.slice(2);
    if (command === 'install') install(root);
    else if (command === 'sync') console.log(`기록 목록 갱신: ${syncIndex(root)}건`);
    else if (command === 'hook') console.log(`[project-history] ${JSON.stringify(captureStaged(root))}`);
    else if (command === 'new') create(root, args);
    else throw new Error('사용법: node scripts/project-history.mjs install | sync | hook | new --slug topic --title "제목"');
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    try { main(); }
    catch (error) {
        console.error(`[project-history] ${error.message}`);
        process.exitCode = 1;
    }
}
