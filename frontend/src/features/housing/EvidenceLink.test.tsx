import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EvidenceLink } from './EvidenceLink';
import { evidenceHref } from '../../shared/ui/helpers';

describe('공식 근거 위치', () => {
    it('확인된 PDF 쪽과 HWPX·엑셀 위치로 연결한다', () => {
        expect(
            evidenceHref(
                'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69306325',
                '공고문 10–11쪽 · 제출서류',
            ),
        ).toMatch(
            /^\/pdf-viewer\.html\?file=69306325&page=10&sha=[0-9a-f]{64}$/,
        );
        expect(
            evidenceHref(
                'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69080506',
                '공식 Q&A · 대리인 방문 제출',
            ),
        ).toMatch(/^\/evidence-viewer\.html\?file=69080506&at=\d+$/);
        expect(
            evidenceHref(
                'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69073340',
                '공급주택목록 · Sheet1',
            ),
        ).toBe('/evidence-viewer.html?file=69073340&at=0');
    });
    it('검증되지 않은 주소와 모르는 위치를 추정하지 않는다', () => {
        expect(evidenceHref('javascript:alert(1)')).toBeNull();
        const original =
            'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69080506';
        expect(evidenceHref(original, '알 수 없는 위치')).toBe(original);
    });
    it('새 위치 링크와 PDF의 원문 해시를 유지한다', () => {
        const sha = 'a'.repeat(64);
        const url = 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123';
        expect(evidenceHref(`${url}#doc2=${sha}.c2VjdGlvbg.YW5jaG9y`)).toBe(
            `/evidence-dynamic.html?file=123&sha=${sha}&location=c2VjdGlvbg&anchor=YW5jaG9y`,
        );
        expect(evidenceHref(`${url}#page=4&sha256=${sha}`)).toBe(
            `/pdf-viewer.html?file=123&page=4&sha=${sha}`,
        );
    });
    it('기존 ZIP 첨부 위임장과 HWPX 별지 위치를 연결한다', () => {
        const zip = evidenceHref(
            'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=65499775',
            '현재 공식 첨부 ZIP · 위임장.hwp',
        );
        const hwpx = evidenceHref(
            'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69191062',
            'HWPX · 본문·별지7',
        );
        expect(zip).toMatch(
            /^\/evidence-dynamic\.html\?file=65499775&sha=[0-9a-f]{64}&location=/,
        );
        expect(hwpx).toMatch(
            /^\/evidence-dynamic\.html\?file=69191062&sha=[0-9a-f]{64}&location=/,
        );
        expect(
            evidenceHref(
                'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69278305',
                '공고문 12쪽 · 대상자 발표',
            ),
        ).toMatch(
            /^\/pdf-viewer\.html\?file=69278305&page=12&sha=[0-9a-f]{64}$/,
        );
    });
    it('관련 Q&A 번호를 원문에 맞게 표시한다', () => {
        render(
            <EvidenceLink
                evidence={{
                    url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69044666',
                    label: '공식 Q&A 신청 관련 14번 · 대리 서류 제출',
                    checkedAt: '2026-11-12',
                }}
            />,
        );
        expect(
            screen.getByRole('link', { name: /신청 관련 15번/ }),
        ).toHaveAttribute(
            'href',
            expect.stringMatching(
                /^\/pdf-viewer\.html\?file=69044666&page=5&sha=[0-9a-f]{64}$/,
            ),
        );
    });
});
