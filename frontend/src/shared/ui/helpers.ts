import verifiedEvidenceLocations from './verifiedEvidenceLocations.json';
import verifiedEvidencePages from './verifiedEvidencePages.json';
import verifiedEvidencePageHashes from './verifiedEvidencePageHashes.json';
import verifiedEvidenceDynamic from './verifiedEvidenceDynamic.json';

export function safeUrl(value: string | null | undefined): value is string {
    if (!value) return false;
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && !url.username && !url.password;
    } catch {
        return false;
    }
}

export function evidenceHref(url: string | null | undefined, label?: string) {
    if (!safeUrl(url)) return null;
    const parsed = new URL(url!);
    if (
        parsed.protocol === 'https:' &&
        parsed.hostname === 'apply.lh.or.kr' &&
        parsed.pathname === '/lhapply/apply/wt/wrtanc/selectWrtancInfo.do' &&
        parsed.searchParams.get('panId') === '2015122300020927' &&
        parsed.searchParams.get('ccrCnntSysDsCd') === '03' &&
        parsed.searchParams.get('uppAisTpCd') === '13' &&
        parsed.searchParams.get('aisTpCd') === '26' &&
        label === 'LH 공식 상세 · 접수처 운영기간 및 정보취약계층 방문 안내'
    ) {
        return '/evidence-viewer.html?file=2015122300020927&at=16';
    }
    const page = parsed.hash.match(
        /^#page=([1-9][0-9]*)(?:&sha256=([0-9a-f]{64}))?$/,
    );
    const id = parsed.searchParams.get('fileid');
    const stableDocumentLocation = parsed.hash.match(
        /^#doc2=([0-9a-f]{64})\.([A-Za-z0-9_-]{1,600})\.([A-Za-z0-9_-]{1,600})$/,
    );
    if (
        stableDocumentLocation &&
        parsed.hostname === 'apply.lh.or.kr' &&
        parsed.pathname === '/lhapply/lhFile.do' &&
        id &&
        /^[0-9]{1,20}$/.test(id)
    ) {
        return `/evidence-dynamic.html?file=${id}&sha=${stableDocumentLocation[1]}&location=${stableDocumentLocation[2]}&anchor=${stableDocumentLocation[3]}`;
    }
    const documentLocation = parsed.hash.match(
        /^#doc=([0-9a-f]{64})\.([0-9]{1,4})\.([A-Za-z0-9_-]+)$/,
    );
    if (
        documentLocation &&
        parsed.hostname === 'apply.lh.or.kr' &&
        parsed.pathname === '/lhapply/lhFile.do' &&
        id &&
        /^[0-9]{1,20}$/.test(id)
    ) {
        return `/evidence-dynamic.html?file=${id}&sha=${documentLocation[1]}&part=${documentLocation[2]}&anchor=${documentLocation[3]}`;
    }

    const verifiedDynamic =
        id && label
            ? (
                  verifiedEvidenceDynamic as Record<
                      string,
                      Record<
                          string,
                          { sha: string; location: string; anchor: string }
                      >
                  >
              )[id]?.[label]
            : undefined;
    if (
        !parsed.hash &&
        verifiedDynamic &&
        parsed.hostname === 'apply.lh.or.kr' &&
        parsed.pathname === '/lhapply/lhFile.do' &&
        id &&
        /^[0-9]{1,20}$/.test(id)
    ) {
        return `/evidence-dynamic.html?file=${id}&sha=${verifiedDynamic.sha}&location=${verifiedDynamic.location}&anchor=${verifiedDynamic.anchor}`;
    }
    const verifiedPage =
        id && label
            ? (verifiedEvidencePages as Record<string, Record<string, number>>)[
                  id
              ]?.[label]
            : undefined;
    const verifiedLocation =
        id && label
            ? (
                  verifiedEvidenceLocations as Record<
                      string,
                      Record<string, number>
                  >
              )[id]?.[label]
            : undefined;
    if (
        parsed.protocol === 'https:' &&
        parsed.hostname === 'apply.lh.or.kr' &&
        parsed.pathname === '/lhapply/lhFile.do' &&
        id &&
        /^[0-9]{1,20}$/.test(id) &&
        verifiedLocation !== undefined
    ) {
        return `/evidence-viewer.html?file=${id}&at=${verifiedLocation}`;
    }
    if (
        parsed.protocol === 'https:' &&
        parsed.hostname === 'apply.lh.or.kr' &&
        parsed.pathname === '/lhapply/lhFile.do' &&
        id &&
        /^[0-9]{1,20}$/.test(id) &&
        (page || verifiedPage)
    ) {
        const verifiedHash =
            !page && verifiedPage
                ? (verifiedEvidencePageHashes as Record<string, string>)[id]
                : undefined;
        const sha = page?.[2] ?? verifiedHash;
        return `/pdf-viewer.html?file=${id}&page=${page?.[1] ?? verifiedPage}${sha ? `&sha=${sha}` : ''}`;
    }
    return url;
}
