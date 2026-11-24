import type { Evidence } from './types';

export const pohangSource = {
    url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69143584',
    sha256: 'f5a88f267a5931d8423e14d62f35ee6cff02ac19e8bbf2404e05c48542567a19',
    checkedAt: '2026-11-21',
} as const;

export function pohangEvidencePage(page: number): Evidence[] {
    return [
        {
            label: `공고문 ${page}쪽 보기`,
            url: `${pohangSource.url}#page=${page}&sha256=${pohangSource.sha256}`,
            checkedAt: pohangSource.checkedAt,
        },
    ];
}
