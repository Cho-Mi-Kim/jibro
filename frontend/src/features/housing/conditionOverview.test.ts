import { describe, expect, it } from 'vitest';
import { conditionOverview, showQuestionEvidence } from './conditionOverview';
import type { Question } from './types';

function question(reason: string): Question {
    return {
        id: 'newly-collected',
        title: '신청자의 성년 또는 미성년 예외를 확인했나요?',
        reason,
        evidence: { label: '공고문', url: null, checkedAt: '' },
    };
}

it('자동 발췌한 조건에서는 숫자와 예외를 남기고 원문 위치 안내만 접는다', () => {
    const q = {
        ...question(
            '원문 구간 공고문 3쪽입니다. 연결된 앞뒤 구간과 예외를 함께 확인하세요.\n\n신청일 현재 만 24세 미만입니다. 다만 법정 보호대상은 예외입니다.',
        ),
        id: 'e-section-new',
    };
    const result = conditionOverview(q, []);
    expect(result.overview).toEqual([
        '신청일 기준 만 24세 미만',
        '다만 법정 보호대상은 예외입니다.',
    ]);
    expect(result.detail).toContain('다만 법정 보호대상은 예외입니다.');
});

it('경주·영천 정정본의 소득표는 해당 원문과 검수 문구가 일치할 때만 표시한다', () => {
    const q = {
        ...question(
            '소득은 도시근로자 월평균소득 150% 이하로 완화합니다. 총자산 3억4,500만원·자동차 4,542만원 등 공고 기준과 자녀 가산을 확인하세요. 주택소유와 자산 기준 전체가 면제되는 공고는 아닙니다.',
        ),
        id: 'income',
        title: '완화된 소득 기준과 자산 기준을 확인했나요?',
        inputKind: 'SINGLE' as const,
        options: [
            { value: 'YES', label: '확인했어요', requiresFollowUp: false },
            { value: 'NO', label: '해당하지 않아요', requiresFollowUp: true },
            {
                value: 'UNKNOWN',
                label: '추가 확인이 필요해요',
                requiresFollowUp: true,
            },
        ],
        constraints: null,
        answerDependencies: null,
        applicability: null,
        evidence: {
            label: '공고문 5쪽',
            url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69355347#page=5',
            checkedAt: '2026-11-18',
        },
    };
    const { structured } = conditionOverview(q, []);
    expect(structured?.sections[0].table?.rows.slice(-2)).toEqual([
        ['7인', '15,728,311원'],
        ['8인', '16,597,228원'],
    ]);
    expect(showQuestionEvidence(q, structured)).toBe(false);
    for (const changed of [
        { ...q, reason: '소득 기준 정정' },
        {
            ...q,
            evidence: {
                ...q.evidence,
                url: q.evidence.url + '&sha256=' + '0'.repeat(64),
            },
        },
    ]) {
        const fallback = conditionOverview(changed, []).structured;
        expect(fallback?.sections.some((section) => section.table)).not.toBe(
            true,
        );
        expect(
            fallback?.sections.flatMap((section) => section.evidence) ?? [],
        ).toEqual([]);
        expect(showQuestionEvidence(changed)).toBe(true);
    }
});

describe('새로 들어온 공고의 조건 요약', () => {
    it('공고 상세의 쉬운 말에도 차량 용도와 실제 금액 및 비교 방향을 유지한다', () => {
        const q = question(
            '자동차 기준은 완화하지 않습니다. 비영업용 승용자동차별 가액 중 최고 가액이 5,451만원 이하인지 확인합니다. 배우자 등 다른 세대원의 차량도 포함합니다.',
        );
        const { preview, detail } = conditionOverview(q, []);
        expect(preview).toBe(
            '영업용이 아닌 승용차 중 가장 비싼 차가 5,451만 원 이하',
        );
        expect(detail).toContain('배우자 등 다른 세대원의 차도 함께 봐요.');
        const changed = question(
            '자동차 기준은 완화하지 않습니다. 영업용 승용차는 별도 기준을 적용합니다.',
        );
        expect(conditionOverview(changed, []).preview).toContain(
            '영업용 승용차는 별도 기준',
        );
    });
    it('익숙한 제목이어도 바뀐 나이와 뒤쪽 숫자·별도 신청 대상을 보존한다', () => {
        const result = conditionOverview(
            question(
                '원칙은 만19세 이상입니다. 신청일 현재 거주지를 확인합니다. 만 24세 미만은 소득 82.5% 이하입니다. 혼인한 미성년자는 별도 유형으로 접수합니다.',
            ),
            [],
        );
        expect(result.overview).toEqual([
            '원칙적으로 만 19세 이상이 신청할 수 있어요.',
            '신청일 기준 거주지를 확인합니다.',
            '만 24세 미만은 소득 82.5% 이하',
            '혼인한 미성년자는 별도 유형으로 접수합니다.',
        ]);
    });

    it('날짜와 소수점, 서술형 숫자, 부정 조건과 그 앞 문장을 함께 유지한다', () => {
        const q = question(
            '기준일은 2027. 3. 2.입니다. 전용면적은 36.5㎡ 이하입니다. 두 자녀가 있는 가구에는 별도 기준이 적용됩니다. 이 경우 분리배우자도 포함합니다. 이 요건만으로는 신청할 수 없습니다.',
        );
        const { overview } = conditionOverview(q, []);
        expect(overview.join(' ')).toContain('2027. 3. 2.');
        expect(overview.join(' ')).toContain('36.5㎡ 이하');
        expect(overview.slice(-3)).toEqual([
            '두 자녀가 있는 가구에는 별도 기준이 적용됩니다.',
            '이 경우 등본이 따로인 배우자도 포함합니다.',
            '이 요건만으로는 신청할 수 없습니다.',
        ]);
        expect(
            conditionOverview({ ...q, reviewRequired: true }, []).overview,
        ).toEqual([]);
    });
});
