import { describe, expect, it } from 'vitest';
import { noticeSummaryText } from './summaryTone';
import type { Question } from './types';

function context(file: string) {
    return {
        questions: [
            {
                id: 'future-condition',
                title: '신청 조건',
                evidence: {
                    url: `https://apply.lh.or.kr/lhapply/lhFile.do?fileid=${file}#page=5`,
                    label: '공고문 5쪽',
                    checkedAt: '2027-01-02',
                },
            },
        ] as Question[],
    };
}

describe('모든 공고의 핵심 요약 말투', () => {
    it('기존 세 공고가 아닌 새 출처와 근거 없는 요약에도 같은 규칙을 적용한다', () => {
        const text =
            '소득은 150% 이하입니다. 신청자가 서명합니다. 예외는 따릅니다.';
        const expected =
            '소득은 150% 이하예요. 신청자가 서명해요. 예외는 따라요.';
        expect(noticeSummaryText(text)).toBe(expected);
        expect(noticeSummaryText(text, context('99999999'))).toBe(expected);
        expect(noticeSummaryText(text, { questions: [] })).toBe(expected);
        expect(noticeSummaryText(text, {})).toBe(expected);
    });

    it('숫자·날짜·예외·금지와 불필요 조건을 그대로 유지한다', () => {
        const text =
            '2027.01.02. 기준 2명 이상, 소득 120% 이하입니다. 다만 65세 이상은 제출하지 않습니다. 태아는 제외되지 않습니다. 중복 신청하지 마세요. 자격을 확정하지 않았습니다. 열람용은 인정되지 않습니다.';
        const result = noticeSummaryText(text);
        expect(result).toBe(
            '2027.01.02. 기준 2명 이상, 소득 120% 이하예요. 다만 65세 이상은 제출하지 않아요. 태아는 제외되지 않아요. 중복 신청하지 말아요. 자격을 확정하지 않았어요. 열람용은 인정되지 않아요.',
        );
        expect(result.match(/\d+(?:[.,]\d+)*/g)).toEqual(
            text.match(/\d+(?:[.,]\d+)*/g),
        );
        expect(text).toContain('제출하지 않습니다.');
    });

    it('안내에서 확인한 불규칙 활용과 현재·과거 표현을 구분한다', () => {
        expect(
            noticeSummaryText(
                '서류를 발급받으세요. 이름을 적으세요. 기준에 맞추세요. 순위를 나눕니다. 순위가 나뉩니다. 기한을 지킵니다. 지위를 잃습니다. 안내를 읽습니다. 비용은 남습니다. 점수가 달라집니다. 서류를 보냅니다. 공고를 확인했습니다.',
            ),
        ).toBe(
            '서류를 발급받아요. 이름을 적어요. 기준에 맞춰요. 순위를 나눠요. 순위가 나뉘어요. 기한을 지켜요. 지위를 잃어요. 안내를 읽어요. 비용은 남아요. 점수가 달라져요. 서류를 보내요. 공고를 확인했어요.',
        );
    });

    it('본문의 인용·코드·URL·파일명에는 표시 규칙을 덮어쓰지 않는다', () => {
        const multiline =
            '공식 원문: "대상은 65세 이상입니다.\n확인이 필요합니다."\n\'신청하지 마세요.\n서류를 보냅니다.\'';
        expect(noticeSummaryText(`${multiline}\n서류를 준비합니다.`)).toBe(
            `${multiline}\n서류를 준비해요.`,
        );
        const quoted =
            '“신청할 수 없습니다.” 「본인이 서명합니다.」 ‘제출하지 마세요.’ "대상입니다." `확인합니다.`';
        const reference =
            'https://example.com/확인합니다.pdf?note=대상입니다. 안내합니다.pdf 준비합니다.hwpx';
        expect(
            noticeSummaryText(`${quoted}\n${reference}\n서류를 준비합니다.`),
        ).toBe(`${quoted}\n${reference}\n서류를 준비해요.`);
    });

    it('줄바꿈과 괄호로 끝난 설명을 처리하고 단어 중간이나 연결 어미는 유지한다', () => {
        expect(
            noticeSummaryText(
                '서명합니다\n(해당자는 제출합니다)\n확인합니다만 예외를 따릅니다. 준비합니다지원',
            ),
        ).toBe(
            '서명해요\n(해당자는 제출해요)\n확인합니다만 예외를 따라요. 준비합니다지원',
        );
    });

    it('명사 받침을 구분하며 날짜·금액·서류명·표의 값에 서술어를 만들지 않는다', () => {
        expect(
            noticeSummaryText(
                '전용면적은 36.5㎡입니다. 부피는 10㎥입니다. 면적은 36.5m²입니다. 부피는 10m³입니다.',
            ),
        ).toBe(
            '전용면적은 36.5㎡예요. 부피는 10㎥예요. 면적은 36.5m²예요. 부피는 10m³예요.',
        );
        expect(
            noticeSummaryText(
                '전자서명 O입니다. 직접 제출 X입니다. 값은 2입니다. 이전 값은 1입니다.',
            ),
        ).toBe(
            '전자서명 O예요. 직접 제출 X예요. 값은 2예요. 이전 값은 1이에요.',
        );
        expect(
            noticeSummaryText(
                '신청자입니다. 대상입니다. 한도는 150%입니다. 모집은 344호입니다.',
            ),
        ).toBe('신청자예요. 대상이에요. 한도는 150%예요. 모집은 344호예요.');
        for (const value of [
            '주민등록등본(상세)',
            '2027.01.02',
            '4,542만 원 이하',
            '10점',
            '모든 신청자',
            '계약 후 자격 확인',
        ])
            expect(noticeSummaryText(value)).toBe(value);
    });

    it('검토한 공고의 명사형 재작성은 해당 출처에만 적용한다', () => {
        const reviewed = '배점순 · 동점이면 오산 전입일이 빠른 순';
        expect(noticeSummaryText(reviewed, context('69203687'))).toBe(
            '배점순으로 뽑고, 동점이면 오산 전입일이 빠른 사람이 우선이에요.',
        );
        expect(noticeSummaryText(reviewed, context('99999999'))).toBe(reviewed);
        expect(noticeSummaryText(reviewed)).toBe(reviewed);
        const changed = '배점순 · 동점이면 신청일이 빠른 순';
        expect(noticeSummaryText(changed, context('69203687'))).toBe(changed);
    });

    it('이미 통일한 문장과 원본 객체는 다시 바꾸지 않는다', () => {
        const source = context('99999999');
        const original = JSON.stringify(source);
        const result = noticeSummaryText(
            '가능한 사람만 신청합니다. 모든 신청자가 대상은 아닙니다.',
            source,
        );
        expect(result).toBe(
            '가능한 사람만 신청해요. 모든 신청자가 대상은 아니에요.',
        );
        expect(noticeSummaryText(result, source)).toBe(result);
        expect(JSON.stringify(source)).toBe(original);
    });
});
