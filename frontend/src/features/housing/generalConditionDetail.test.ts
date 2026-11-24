import { expect, it } from 'vitest';
import { conditionOverview } from './conditionOverview';
import { generalConditionDetail } from './generalConditionDetail';
import { noticeConditionHeading } from './plainNoticeCondition';
import type { Question } from './types';

function question(reason: string): Question {
    return {
        id: 'future-condition',
        title: '신청 조건',
        reason,
        evidence: {
            label: '공식 원문',
            url: 'https://example.org/new.pdf',
            checkedAt: '2027-01-01',
        },
    };
}

it('새 공고의 기준과 예외·확인 사항을 나누며 실제 수치와 부정 조건을 남긴다', () => {
    const q = question(
        '공고일 현재 만 19세 이상이 원칙입니다. 다만 혼인한 미성년 세대주는 예외입니다. 예외의 증빙은 LH에 문의하세요. 계약 때까지 자격을 유지해야 합니다.',
    );
    const original = structuredClone(q);
    const result = conditionOverview(q, []);
    expect(result.preview).toContain('19세');
    expect(result.preview).toContain('예외·별도 기준 있음');
    expect(result.structured?.sections.map((section) => section.text)).toEqual([
        '공고일 기준 만 19세 이상이 원칙이에요.',
        '예외·별도 기준\n다만 혼인한 미성년 세대주는 예외예요.',
        '추가 확인\n예외의 증빙은 LH에 문의해요.',
        '계약 때까지 자격을 유지해야 해요.',
    ]);
    expect(q).toEqual(original);
    expect(
        result.structured?.sections.flatMap((section) => section.evidence),
    ).toEqual([]);
});

it('순위별 대상을 표로 나누고 뒤의 예외·공통 조건을 남긴다', () => {
    const detail = generalConditionDetail(
        '1순위는 신생아가구 또는 법정 보호대상 한부모, 2순위는 6세 이하 자녀의 일반 한부모입니다. 태아를 포함하며 공통 소득·자산·무주택 요건을 따릅니다.',
    );
    expect(detail?.sections[0].table?.rows).toEqual([
        ['1순위', '신생아가구 또는 법정 보호대상 한부모'],
        ['2순위', '6세 이하 자녀의 일반 한부모예요.'],
    ]);
    expect(detail?.sections[1].text).toContain('태아를 포함');
    expect(detail?.sections[1].text).toContain('소득·자산·무주택');
});

it('원문에서 분리된 배점 행을 표로 옮기며 감점·구간·설명을 누락하지 않는다', () => {
    const text =
        '구분 / 100점 / 배점기준\n거주기간 (30점) / 1년 미만 / 22 / 연속 거주기간\n계약 이력 / 최근 1년 / -5점 / 가구원 수 변동 시 예외\n계약 이력 / 1년 초과 3년 이내 / -3점';
    const detail = generalConditionDetail(text, '선정 순위와 배점');
    const table = detail?.sections[0].table;
    expect(table?.headers).toEqual(['항목', '기준']);
    expect(table?.rows).toContainEqual([
        '계약 이력',
        '최근 1년 / -5점 / 가구원 수 변동 시 예외',
    ]);
    expect(detail?.text.match(/-?\d+/g)).toEqual(text.match(/-?\d+/g));
});

it('번호가 붙은 배점은 구간과 점수를 나란히 놓고 모호한 주석은 원래 행에 남긴다', () => {
    const detail = generalConditionDetail(
        '① 계속거주기간 (대전광역시) / 가. 1년 이상 3년 미만 : 1점 나. 3년 이상 5년 미만 : 2점 다. 5년 이상 : 3점\n② 자녀 수 / 가. 2자녀 : 2점 / 나. 3자녀 이상 : 3점 * 태아 포함',
    );
    expect(detail?.sections[0].table?.rows).toEqual([
        ['① 계속거주기간 (대전광역시)', '가. 1년 이상 3년 미만', '1점'],
        ['① 계속거주기간 (대전광역시)', '나. 3년 이상 5년 미만', '2점'],
        ['① 계속거주기간 (대전광역시)', '다. 5년 이상', '3점'],
    ]);
    expect(detail?.text).toContain('3점 * 태아 포함');
});

it('가구원 수별 금액은 행으로 표시하고 날짜·소수점·태아 예외는 그대로 둔다', () => {
    const detail = generalConditionDetail(
        '2027. 3. 2. 기준입니다.\n1인 가구 · 70% 기준 2,000,001원 / 90% 기준 3,000,001원\n2인 가구 · 70% 기준 4,000,002원 / 90% 기준 5,000,002원\n태아를 포함합니다. 전용면적은 36.5㎡ 이하입니다.',
    );
    expect(detail?.sections[1].table?.rows).toEqual([
        ['1인 가구', '2,000,001원', '3,000,001원'],
        ['2인 가구', '4,000,002원', '5,000,002원'],
    ]);
    expect(detail?.text).toContain('2027. 3. 2.');
    expect(detail?.text).toContain('태아를 포함해요.');
    expect(detail?.text).toContain('36.5㎡ 이하예요.');
});

it('신혼Ⅱ의 2~8인별 두 소득 한도는 각각 맞는 가구원 수에 연결한다', () => {
    const result = conditionOverview(
        question(
            '기본130%, 본인·배우자 모두 소득이 있으면200% 이하입니다. 2~8인가구의130% 금액은8,212,778 / 10,618,958 / 11,442,863 / 12,125,081 / 12,878,142 / 13,631,203 / 14,384,265원, 200%는12,319,167 / 16,336,858 / 17,604,404 / 18,653,970 / 19,812,526 / 20,971,082 / 22,129,638원입니다. 2인가구 금액에는10%p 가산이 이미 포함되어 있고 태아도 가구원수에 포함합니다. 예비신혼은 혼인으로 구성할 세대의 소득을 봅니다.',
        ),
        [],
    );
    const table = result.structured?.sections.find(
        (section) => section.table,
    )?.table;
    expect(table?.rows).toEqual([
        ['2인 가구', '8,212,778원', '12,319,167원'],
        ['3인 가구', '10,618,958원', '16,336,858원'],
        ['4인 가구', '11,442,863원', '17,604,404원'],
        ['5인 가구', '12,125,081원', '18,653,970원'],
        ['6인 가구', '12,878,142원', '19,812,526원'],
        ['7인 가구', '13,631,203원', '20,971,082원'],
        ['8인 가구', '14,384,265원', '22,129,638원'],
    ]);
    expect(table?.headers).toEqual(['가구원 수', '130%', '200%']);
    expect(result.preview).toBe(
        '소득 기준은 기본 130% 이하, 부부 모두 소득이 있으면 200% 이하',
    );
    expect(result.structured?.text).toContain('10%p 가산이 이미 포함');
    expect(result.structured?.text).toContain('태아도 가구원수에 포함');
    expect(result.structured?.text).toContain(
        '예비신혼부부는 결혼 후 구성할 세대',
    );
    expect(
        generalConditionDetail(
            '금액은 130 / 200원이며 두 유형은 별도 기준입니다.',
        )?.sections.some((section) => section.table),
    ).not.toBe(true);
});

it('정확한 열 수를 가진 표만 기존 헤더를 사용하고 값을 만들지 않는다', () => {
    const result = generalConditionDetail(
        '가구원 수 | 월소득 한도\n1인 | 5,720,045원\n2인 | 8,799,405원',
    );
    expect(result?.sections[0].table).toEqual({
        title: '세부 기준',
        headers: ['가구원 수', '월소득 한도'],
        rows: [
            ['1인', '5,720,045원'],
            ['2인', '8,799,405원'],
        ],
    });
    expect(
        generalConditionDetail('아직 확인되지 않은 새 기준'),
    ).toBeUndefined();
});

it('순위표 뒤에 가구원 표가 이어져도 각각의 제목과 행을 구분한다', () => {
    const detail = generalConditionDetail(
        '1순위는 해당 지역 거주자\n2순위는 인접 지역 거주자\n1인 가구 · 전용면적 36.5㎡\n2인 가구 · 전용면적 45㎡',
    );
    expect(detail?.sections.map((section) => section.table?.title)).toEqual([
        '순위별 신청 대상',
        '가구원 수별 기준',
    ]);
    const family = generalConditionDetail(
        '1인 가구 · 전용면적 36.5㎡\n2인 가구 · 전용면적 45㎡',
    );
    expect(family?.preview).toBe('가구원 수별 기준 확인');
    expect(family?.preview).not.toContain('소득');
    const different = generalConditionDetail(
        '1인 가구 · 70% 기준 2,000,001원 / 90% 기준 3,000,001원\n2인 가구 · 60% 기준 4,000,002원 / 80% 기준 5,000,002원',
    );
    expect(different?.sections[0].table?.headers).toEqual([
        '가구원 수',
        '기준',
    ]);
    expect(different?.sections[0].table?.rows[1][1]).toBe(
        '60% 기준 4,000,002원 / 80% 기준 5,000,002원',
    );
});

it('검수 필요 조건은 새 요약이나 승인된 구조로 만들지 않는다', () => {
    const q = {
        ...question('소득 150% 이하입니다. 다만 다른 표는 130% 이하입니다.'),
        reviewRequired: true,
    };
    const result = conditionOverview(q, []);
    expect(result.preview).toBe('');
    expect(result.overview).toEqual([]);
    expect('structured' in result).toBe(false);
    expect(result.detail).toBe(q.reason);
});

it('신혼·신생아 공고의 미성년 예외 세 가지를 각각 표시하고 계약 증빙은 유지한다', () => {
    const q = question(
        '원칙은 만19세 이상입니다. 자녀가 있는 미성년 세대주, 직계존속의 사망·실종선고·행방불명 등으로 형제자매를 부양해야 하는 미성년 세대주, 외국인 부·모가 있는 한부모가족의 내국인 미성년 세대주는 예외가 있습니다. 계약에는 법정대리인 동의 또는 대리가 필요하며 다른 자격요건도 확인해야 합니다.',
    );
    const result = conditionOverview(q, []);
    expect(result.detail).toContain('• 자녀가 있어요.\n• 부모·조부모');
    expect(result.detail).toContain('사망·실종선고·행방불명');
    expect(result.detail).toContain(
        '• 부모 중 외국인이 있는 한부모가족이며, 미성년 세대주는 한국 국적이에요.',
    );
    expect(result.detail).toContain('계약에는 법정대리인 동의 또는 대리');
    expect(result.detail).not.toContain('같은 등본');
    expect(result.preview).toContain('만 19세부터 신청');
    expect(result.preview).not.toContain('이상부터');
});

it('신청 대상 문구에 나이 순서나 선정 우선순위를 새로 붙이지 않는다', () => {
    const result = conditionOverview(
        question(
            '대학생이 신청할 수 있어요. 다만 정해진 소득 기준을 따라야 해요.',
        ),
        [],
    );
    expect(result.preview).toBe(
        '대학생이 신청할 수 있어요 · 예외·별도 기준 있음',
    );
    expect(result.preview).not.toContain('대학생부터');
    expect(
        conditionOverview(question('원칙은 만19세 이상입니다.'), []).preview,
    ).toBe('원칙 · 만 19세부터 신청');
});

it('선택지 조작 안내만 설명에서 빼고 자격·서류 선택 조건과 원본은 남긴다', () => {
    const q = question(
        '소득은 150% 이하입니다. 해당 항목을 함께 선택하되, 해당 없음·잘 모르겠어요는 다른 항목과 함께 선택하지 마세요. 면제받지 않는 신청자는 금융정보 동의서와 자산보유사실확인서를 모두 제출하고, 아래에 해당하는 자산·부채 증빙을 함께 선택하세요.',
    );
    const original = structuredClone(q);
    const result = conditionOverview(q, []);
    expect(result.detail).not.toContain('해당 없음·잘 모르겠어요');
    expect(result.detail).toContain('면제받지 않는 신청자는 금융정보');
    expect(result.detail).toContain('자산·부채 증빙');
    expect(q).toEqual(original);
});

it('자주 수집되는 딱딱한 조건 제목을 질문으로 표시한다', () => {
    expect(noticeConditionHeading('가구원 수에 따른 소득 기준')).toBe(
        '가구원 수별 소득 한도는 얼마인가요?',
    );
    expect(noticeConditionHeading('나이 기준과 미성년 예외')).toBe(
        '몇 살부터 신청할 수 있나요?',
    );
    expect(noticeConditionHeading('함께 심사받는 가족')).toBe(
        '어떤 가족을 함께 심사하나요?',
    );
});
