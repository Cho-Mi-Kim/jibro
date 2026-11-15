import { describe, expect, it } from 'vitest';
import { onlineIssuanceLinks } from './onlineIssuance';

const links = (title: string, method = '') =>
    onlineIssuanceLinks({ title, method, issuer: '공고 지정 기관' });

describe('공고별 공식 서류 발급 안내', () => {
    it.each([
        ['신청자 주민등록표등본(상세)', '13100000015'],
        ['분리 배우자·종전 세대주 등본', '13100000015'],
        ['주민등록표 등·초본', '13100000015'],
        ['신청자 자녀의 상세 가족관계증명서', 'efamily.scourt.go.kr'],
        ['입양관계증명서와 가족관계증명서', 'efamily.scourt.go.kr'],
        ['혼인관계증명서(상세)', 'efamily.scourt.go.kr'],
        ['지원대상 한부모가족 증명서', '10601000001'],
        ['주거급여 수급자증명서', '14600000280'],
        ['차상위계층 증빙', '13520000098'],
        ['인감증명 방식 위임 서류', '13100000025'],
        ['본인서명 방식 위임 서류', '13110000047'],
        ['외국인등록사실증명서', '12700000027'],
        ['배점용 북한이탈주민 등록확인서', '12500000065'],
        ['청약통장 순위(가입)확인서', 'applyhome.co.kr'],
        ['건강보험 자격득실확인서', 'PG4CADM0107'],
    ])('%s의 공식 발급처를 공고 ID 없이 찾는다', (title, target) => {
        expect(links(title)).toHaveLength(1);
        expect(links(title)[0].url).toContain(target);
    });
    it.each([
        '임신진단서',
        '임대차계약서 사본',
        '외국인등록증 사본 또는 국내거소신고증',
        '청약통장 사본',
        '개인정보 수집·이용 동의서',
        '구직급여 수급자격 인정 증빙',
        '법인인감증명서',
        '전자본인서명사실확인서',
        '주민등록표등본 제출면제',
    ])('%s에 다른 증명서를 연결하지 않는다', (title) => {
        expect(links(title, '주민등록표등본의 제출 요건 참조')).toEqual([]);
    });
    it('기존 공고 첨부 양식을 우선한다', () => {
        const url =
            'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123#page=4';
        expect(
            onlineIssuanceLinks({
                title: '가족관계증명서',
                issuer: 'LH 첨부',
                issueUrl: url,
            }),
        ).toEqual([{ label: 'LH 첨부', url }]);
    });
    it('복수 증명서와 방문 발급을 구분한다', () => {
        expect(links('인감증명서 또는 본인서명사실확인서')).toHaveLength(2);
        expect(links('본인서명사실확인서')[0].note).toContain('방문');
        expect(links('외국인등록·국내거소신고 사실증명')).toHaveLength(2);
        expect(links('장애인증명서·해당 중증장애인 확인서')).toHaveLength(2);
    });
    it('대학 재학 증명은 교육 단계가 확인될 때 안내한다', () => {
        expect(
            links('재학증명서', '현재 재학 중인 대학의 재학증명서')[0].url,
        ).toContain('13404000010');
        expect(
            links('초등학교 재학증명서').some((link) =>
                link.url.includes('13404000010'),
            ),
        ).toBe(false);
    });
    it('묶음의 주제와 발급 기준이 모두 일치하는 증명서만 표시한다', () => {
        const method =
            '국민연금 가입증명서와 건강보험 자격득실확인서 중 해당 서류를 확인하세요.';
        expect(links('연금·건강보험·소득활동 증빙', method)).toHaveLength(2);
        expect(links('공급신청서', method)).toEqual([]);
    });
});
