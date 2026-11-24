import { expect, it } from 'vitest';
import { documentInstructions } from './documentInstructions';
import { documentSummary } from './documentSummary';

it('길이와 관계없이 서명·발급기준·예외를 첫 안내 다음에도 모두 보존한다', () => {
    const result = documentInstructions(
        '공고일 이후 발급한 서류를 준비합니다. 세대원 전원이 서명합니다. 다만 14세 미만은 법정대리인이 서명합니다. 주민등록번호 13자리가 모두 보이게 발급받으세요. 열람용은 제출할 수 없습니다.',
    );
    expect(result.instruction).toBe('공고일 이후 발급한 서류를 준비해요.');
    expect(result.details).toEqual([
        '세대원 전원이 서명해요.',
        '다만 14세 미만은 법정대리인이 서명해요.',
        '주민등록번호 13자리가 모두 보이게 발급받아요.',
        '열람용은 제출할 수 없어요.',
    ]);
});

it('명시된 제출대상과 발급방법을 분리하고 날짜 범위와 문서의 주체를 유지한다', () => {
    const result = documentInstructions(
        '• (제출대상) 아래 해당자 - 배우자가 외국인인 경우 - 65세 이상 부양가족의 배우자를 확인할 수 없으면 피부양자의 가족관계증명서를 내요. 이 공고에 제출할 때는 주민등록번호 13자리가 모두 보이게 발급받으세요. • (발급방법) 2027.01.02 - 2027.01.10 발급분을 제출합니다. • (주의) 열람용은 제출할 수 없습니다.',
    );
    expect(result.audience).toEqual([
        '배우자가 외국인인 경우',
        '65세 이상 부양가족의 배우자를 확인할 수 없으면 피부양자의 가족관계증명서를 내요.',
    ]);
    expect(result.instruction).toContain('13자리');
    expect(result.details).toEqual([
        '발급방법 · 2027.01.02 - 2027.01.10 발급분을 제출해요.',
        '주의 · 열람용은 제출할 수 없어요.',
    ]);
});

it('본문 속 제출대상 단어만으로 제출 기준이나 서류명·근거를 바꾸지 않는다', () => {
    const document = {
        id: 'future-form',
        title: '새 기관 확인서',
        issuer: '새 기관',
        issueUrl: null,
        method: '자산확인서 제출대상 중 해당자는 사본을 냅니다. 다만 원본은 반환하지 않습니다.',
        evidence: {
            label: '공식 양식',
            url: 'https://example.org/new.pdf#page=8',
            checkedAt: '2027-01-01',
        },
    };
    const original = structuredClone(document);
    const summary = documentSummary(document, { questions: [] });
    expect(summary.audience).toBe('모든 신청자');
    expect(summary.audienceDetails).toEqual([]);
    expect(summary.methodDetails).toEqual(['다만 원본은 반환하지 않아요.']);
    expect(document).toEqual(original);
});

it.each(['UNKNOWN', 'NOT_APPLICABLE'] as const)(
    '제출 대상 목록이 있어도 저장 당시 %s 상태를 바꾸지 않는다',
    (necessity) => {
        const result = documentSummary(
            {
                id: 'saved-document',
                title: '가족 증빙',
                issuer: '행정기관',
                issueUrl: null,
                method: '• (제출대상) 아래 해당자 - 배우자가 외국인인 경우',
                necessity,
                state: 'UNCHECKED',
                note: '내 메모',
                evidence: {
                    label: '공식 원문',
                    url: 'https://example.org/saved.pdf',
                    checkedAt: '2026-11-20',
                },
            },
            { questions: [] },
            { historical: true },
        );
        expect(result.audience).toBe(
            necessity === 'UNKNOWN' ? '해당 여부 추가 확인' : '당시 해당 없음',
        );
        expect(result.audienceDetails).toEqual(['배우자가 외국인인 경우']);
    },
);
