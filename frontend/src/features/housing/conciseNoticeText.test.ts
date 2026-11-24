import { describe, expect, it } from 'vitest';
import {
    conciseNoticeText,
    summaryNarrationSentences,
} from './conciseNoticeText';
import { noticeSupportSummaryRows } from './supportSummaryRows';
import { qualificationSummary } from './qualificationSummary';
import type { Notice } from './types';

function notice(summary: string, eligibility?: string): Notice {
    return {
        id: 'new-notice',
        summary,
        questions: [],
        qualificationSummary: {
            eligibility:
                eligibility ??
                '신청 자격은 조건 탭에서 항목별로 확인해 주세요.',
            selection: '선정 기준은 조건 탭과 공식 공고에서 확인해 주세요.',
            detail: null,
            evidence: null,
            reviewRequired: false,
        },
    } as unknown as Notice;
}

describe('공고의 공통 축약 규칙', () => {
    it('한글 목록의 다.를 문장 끝으로 오인하지 않는다', () => {
        expect(
            summaryNarrationSentences(
                '가. 1년 이상 : 1점 나. 3년 이상 : 2점 다. 5년 이상 : 3점',
            ),
        ).toEqual(['가. 1년 이상 : 1점 나. 3년 이상 : 2점 다. 5년 이상 : 3점']);
        expect(
            summaryNarrationSentences(
                '금액은 3,000,000 / 4,500,000원입니다. 65세 이상은 예외입니다.',
            ),
        ).toEqual([
            '금액은 3,000,000 / 4,500,000원입니다.',
            '65세 이상은 예외입니다.',
        ]);
    });

    it('인용은 보존하면서 밖의 가족관계 설명은 쉬운 말로 바꾼다', () => {
        const source =
            '“65세 이상 직계존속”의 사회취약계층 가산점을 받고자 하는 신청자 중 부모·자녀간 육아지원세대 배점을 받고자 할 경우 신청자 명의 증명서를 내요. 혼인으로 구성될 세대에 신청자와 예비배우자 외의 사람을 포함하는 경우 해당 세대원과의 관계를 확인할 수 있는 신청자 또는 예비배우자의 증명서를 내요.';
        expect(conciseNoticeText(source)).toBe(
            '“65세 이상 직계존속”의 사회취약계층 가점을 받으려는 신청자 중 부모·자녀 육아지원 가점을 받는 경우 신청자 명의 증명서를 내요. 결혼 후 함께 살 세대에 두 사람 외의 가족을 포함하면 그 가족과의 관계가 나오는 신청자 또는 예비배우자의 증명서를 내요.',
        );
    });

    it('대상과 공급목록이 섞인 안내도 모집호수는 지원 내용으로 나누고 운영 절차는 펼쳐 보인다', () => {
        const source =
            '경기남부 비분양전환 든든전세 정정 공고입니다. 자격 기준일은 2026.09.30이며 정정 게시일 10/19과 구분합니다. 수도권 거주 무주택세대구성원 대상, 소득·자산 제한 없는 순수 전세형, 정정 공급목록 344호입니다. 미성년 예외·가점순 선정과 추첨 단계·신청기간 중 수정 기준을 구분해 안내합니다. 계약은 개별 안내입니다.';
        const rows = noticeSupportSummaryRows(notice(source));
        expect(rows[0].text).toBe(
            '자격 기준일은 2026.09.30이에요. 수도권 거주 무주택세대구성원이 대상이며, 소득·자산 제한은 없어요. 미성년자는 일부 예외가 있어요.',
        );
        expect(rows[1].text).toContain('순수 전세형 · 정정 공급목록 344호');
        expect(rows[0].text).not.toMatch(/가점순|추첨|신청기간/u);
        expect(rows[2].details).toContain(
            '가점순 선정과 추첨 단계·신청기간 중 수정 기준을 구분해 안내해요.',
        );
        const all = rows
            .flatMap((row) => [row.text, ...(row.details ?? [])])
            .join(' ');
        expect(all.match(/\d[\d,.]*/g)?.sort()).toEqual(
            source.match(/\d[\d,.]*/g)?.sort(),
        );
    });

    it('다른 지역의 같은 서식도 실제 기준일·호수·면적과 미성년 예외를 사용한다', () => {
        const source =
            '새 지역 분양전환형 공고입니다. 기준일은 2027.02.10, 공급목록51호·실사용84㎡ 이상 군을 확인했습니다. 초기 임대 신청은 소득·자산 제한 없는 무주택 요건입니다. 이번 공고의 전국 신청 허용과 미성년 예외를 적용하고, 신청기간 중 수정·마감 후 변경 금지 및 가점순 선정·추첨 단계를 구분합니다. 분양전환은 6년 뒤 가능하지만 확정 약속은 아닙니다.';
        const rows = noticeSupportSummaryRows(notice(source));
        expect(rows[0].text).toContain('2027.02.10');
        expect(rows[0].text).toContain('전국 신청 허용 · 미성년자는 일부 예외');
        expect(rows[0].text).not.toMatch(/추첨|가점|신청기간/u);
        expect(rows[1].text).toContain('51호 · 실사용 84㎡ 이상');
        expect(rows[1].text).toContain('확정 약속은 아니에요');
        expect(rows[2].details).toContain(
            '신청기간 중 수정·마감 후 변경 금지 및 가점순 선정·추첨 단계를 구분해요.',
        );
    });

    it('새 출처도 지원 대상과 임대 방법을 짧게 표시한다', () => {
        const n = notice(
            '충주시에 거주하는 성년 무주택세대구성원 중 공고의 자격 유형과 소득·자산 기준을 충족하는 가구가 대상입니다. 충주연수2 영구임대주택의 전용 31.32㎡ 주택을 자격 유형별 보증금과 월 임대료로 공급합니다. 예비입주자로 선정되면 빈집이 생겼을 때 대기순번에 따라 계약을 안내받습니다.',
        );
        const before = structuredClone(n);
        expect(noticeSupportSummaryRows(n)).toEqual([
            {
                label: '지원 대상',
                text: '충주시에 사는 성년 무주택세대구성원 중 공고의 자격 유형과 소득·자산 기준에 맞는 가구가 대상이에요.',
            },
            {
                label: '지원 내용',
                text: '충주연수2 영구임대주택의 전용 31.32㎡ 주택을 유형별 보증금·월 임대료로 공급해요.',
            },
            {
                label: '함께 확인',
                text: '예비입주자는 빈집이 생기면 순번대로 계약을 안내받아요.',
            },
        ]);
        expect(n).toEqual(before);
    });

    it('전세 지원 절차는 한 문장으로 줄여도 지원 한도를 유지한다', () => {
        expect(
            conciseNoticeText(
                '입주자로 선정되면 지원 한도 안에서 원하는 전세주택을 찾고, LH가 집주인과 계약한 뒤 다시 임대해 줍니다.',
            ),
        ).toBe(
            '선정되면 지원 한도 안에서 고른 전세주택을 LH가 계약해 다시 임대해요.',
        );
    });

    it('가족관계 서류의 제출 사유와 결혼 후 심사 세대를 쉬운 말로 바꾼다', () => {
        expect(
            conciseNoticeText(
                '주민등록표등본에서 배우자 확인이 불가한 경우 상세 증명서를 내요. 혼인으로 구성될 세대의 이름을 기재하고 주민등록표등본상 관계를 기재합니다.',
            ),
        ).toBe(
            '등본에서 배우자를 확인할 수 없는 경우 상세 증명서를 내요. 결혼 후 함께 살 세대의 이름을 적고 등본상 관계를 적어요.',
        );
        expect(conciseNoticeText('“주민등록표등본상 관계를 기재합니다.”')).toBe(
            '“주민등록표등본상 관계를 기재합니다.”',
        );
    });

    it('숫자·미성년 예외·적용 제외와 부정을 삭제하지 않는다', () => {
        const source =
            '공고의 완화된 소득 150% 이하 기준을 충족하는 신청자가 대상입니다. 65세 이상은 별도 순위이며 미성년 세대주는 예외가 있습니다. 소득·총자산 기준은 적용하지 않지만 자동차 4,542만 원 기준은 적용합니다.';
        const result = conciseNoticeText(source);
        expect(result).toBe(
            '완화된 소득 150% 이하 기준에 맞는 신청자가 대상이에요. 65세 이상은 별도 순위이며 미성년 세대주는 예외가 있어요. 소득·총자산 기준은 적용하지 않지만 자동차 4,542만 원 기준은 적용해요.',
        );
        expect(result.match(/\d[\d,.]*/g)).toEqual(source.match(/\d[\d,.]*/g));
    });

    it('긴 종합 안내를 나눠도 날짜·시각·금액·부정과 모든 문장을 남긴다', () => {
        const n = notice(
            '제주 분양전환형 든든전세 공고입니다. 공급목록은 48호입니다. 초기 임대 신청은 소득·자산 제한 없는 무주택 요건입니다. 신청은 10/27 10시~10/28 16시 온라인만 받습니다. 우편·최초 현장 신청은 받지 않습니다. 계약은 개별 안내입니다. 분양전환은 6년 뒤 가능하며 확정 분양 약속은 아닙니다.',
        );
        const rows = noticeSupportSummaryRows(n);
        expect(rows.map((row) => row.label)).toEqual([
            '지원 대상',
            '지원 내용',
            '함께 확인',
        ]);
        expect(rows[0].text).toContain('소득·자산 제한 없는 무주택');
        expect(rows[1].text).toContain('48호');
        expect(rows[1].text).toContain('확정 분양 약속은 아니에요');
        expect(rows[2].details).toEqual([
            '신청은 10/27 10시~10/28 16시 온라인만 받아요.',
            '우편·최초 현장 신청은 받지 않아요.',
            '계약은 개별 안내예요.',
        ]);
        const displayed = rows
            .flatMap((row) => [row.text, ...(row.details ?? [])])
            .join(' ');
        expect(displayed.match(/\d[\d,.]*/g)?.sort()).toEqual(
            n.summary.match(/\d[\d,.]*/g)?.sort(),
        );
    });

    it('신청 자격의 기본 안내는 이미 공개된 지원 대상으로 대체한다', () => {
        const n = notice(
            '지원 대상: 만 65세 이상이며 소득 70% 이하 기준을 충족하는 무주택 신청자입니다.\n지원 내용: 고령자복지주택을 공급합니다.',
        );
        expect(qualificationSummary(n).rows[0].text).toBe(
            '만 65세 이상이며 소득 70% 이하 기준에 맞는 무주택 신청자예요.',
        );
        expect(
            qualificationSummary(
                notice(n.summary, '입주 시까지 무주택이어야 합니다.'),
            ).rows[0].text,
        ).toBe('입주 시까지 무주택이어야 해요.');
    });

    it('긴 안내도 자격 기준일과 미성년 예외는 접힌 지원 대상에 남긴다', () => {
        const rows = noticeSupportSummaryRows(
            notice(
                '새 지역 든든전세 공고입니다. 자격 기준일은 2027.01.03입니다. 무주택세대구성원에게 40호를 공급합니다. 미성년 세대주도 예외에 해당하면 신청할 수 있습니다. 신청은 2월 3일입니다. 계약은 개별 안내입니다.',
            ),
        );
        expect(rows[0].text).toContain('2027.01.03');
        expect(rows[0].text).toContain('미성년 세대주도 예외');
        expect(rows[2].details).toHaveLength(2);
    });

    it('제목만 있는 미검수 자료에서 자격이나 지원 내용을 만들지 않는다', () => {
        const n = notice('LH의 ‘미래 단지 9호 모집’ 공고입니다.');
        expect(noticeSupportSummaryRows(n)).toEqual([
            { label: null, text: 'LH의 ‘미래 단지 9호 모집’ 공고예요.' },
        ]);
        expect(qualificationSummary(n).rows[0].text).toBe(
            '신청 자격은 조건 탭에서 항목별로 확인해요.',
        );
    });

    it('인용·주소를 고치거나 명사·숫자 표에 동사를 덧붙이지 않는다', () => {
        expect(
            conciseNoticeText('“공고의 소득 기준을 충족하는 신청자입니다.”'),
        ).toBe('“공고의 소득 기준을 충족하는 신청자입니다.”');
        expect(conciseNoticeText('https://example.org/공고의_소득.pdf')).toBe(
            'https://example.org/공고의_소득.pdf',
        );
        for (const value of ['36.5㎡', '1~2인', '20점', '주민등록등본(상세)'])
            expect(conciseNoticeText(value)).toBe(value);
        expect(
            conciseNoticeText('예외에 해당하는 경우의 증빙만 제출합니다.'),
        ).toBe('예외에 해당하는 경우의 증빙만 제출해요.');
    });
});
