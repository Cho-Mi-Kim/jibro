import type { Notice } from './types';
import {
    samryeConditionDetail,
    samryeEvidencePage as page,
    samryeReviewedQuestion,
} from './samryeConditionDetail';
import { sectioned } from './structuredConditionDetail';

function reviewed(notice: Notice, ids: string[]) {
    return ids.every((id) => !!samryeReviewedQuestion(notice, id));
}

export function samryeSupportSummary(notice: Notice) {
    if (!reviewed(notice, ['category', 'rank'])) return;
    return {
        rows: [
            {
                label: '지원 대상',
                text: '대학생·취업준비생, 청년·사회초년생, 65세 이상인 사람, 주거급여수급자가 대상이에요.',
            },
            {
                label: '지원 내용',
                text: '완주삼례 A-1 행복주택 21A·26A형의 예비입주자 190명을 모집해요.',
            },
        ],
        detail: sectioned([
            '빈집이 생기면 기존 대기자 다음 순번부터 입주해요. 실제 입주까지 수개월~1년 이상 걸릴 수 있어요.',
            page(1),
            {
                table: {
                    title: '이번에 모집하는 주택',
                    headers: ['주택형', '전용면적', '예비입주자'],
                    rows: [
                        ['21A', '21.52㎡', '140명'],
                        ['26A', '26.06㎡', '50명'],
                    ],
                },
                evidence: page(4),
            },
            '보증금과 월세는 신청 유형에 따라 달라요. 아래는 보증금·월세 전환 전, 공고일 기준 금액이에요. 실제 계약할 때 바뀔 수 있어요.',
            {
                table: {
                    title: '주택형별 기본 임대료',
                    headers: ['주택형', '보증금', '월세'],
                    rows: [
                        ['21A', '1,164만~1,474만 4천 원', '60,140~76,170원'],
                        ['26A', '1,410만~1,786만 원', '72,850~92,270원'],
                    ],
                },
                evidence: page(5),
            },
            '분양으로 전환되지 않는 임대주택이에요.',
            page(3),
        ]),
    };
}

export function samryeQualificationSummary(notice: Notice) {
    if (
        notice.qualificationSummary?.reviewRequired ||
        !reviewed(notice, ['category', 'household', 'vehicle', 'rank'])
    )
        return;
    const details = ['category', 'rank'].flatMap((id) => {
        const question = samryeReviewedQuestion(notice, id);
        const detail = question && samryeConditionDetail(question);
        return detail ? [detail] : [];
    });
    return {
        rows: [
            {
                label: '신청 자격',
                text: '이번 모집은 소득·총자산 기준을 빼고 심사해요. 유형별 주택·자동차 기준을 확인해요.',
            },
            {
                label: '선정 순위',
                text: '주택형별 우선 대상을 먼저 뽑고, 남으면 추가 대상을 뽑아요. 대학생·청년은 지역 순위 후 추첨해요.',
            },
        ],
        details,
    };
}

export function samryeSubmissionSummary(notice: Notice) {
    const expected = [
        [
            'visit',
            '2026-11-19',
            '접수 회차 · 방문 대행(정보취약계층) 10:00~16:00 · 12:00~13:00 제외',
        ],
        ['subject', '2026-12-01', '서류 제출 대상자 발표 (16:00 이후)'],
        ['docs-start', '2026-12-01', '서류 제출 시작'],
        ['docs-end', '2026-12-04', '서류 제출 마감 (등기우편 소인 기준)'],
    ];
    if (
        !reviewed(notice, ['method']) ||
        !expected.every(([id, date, title]) =>
            notice.schedules?.some(
                (item) =>
                    item.id === id &&
                    item.date === date &&
                    item.title === title &&
                    !item.timingDescription,
            ),
        ) ||
        notice.reviewIssues?.some((issue) => issue.area === 'SCHEDULE')
    )
        return;
    return sectioned([
        {
            table: {
                title: '신청 방법별 서류 제출',
                headers: ['신청 방법', '서류 제출 시점'],
                rows: [
                    [
                        '인터넷·모바일',
                        '12월 1일 16시 이후 대상자를 발표해요. 선정되면 12월 1~4일에 내요.',
                    ],
                    ['현장', '11월 19일 신청할 때 함께 내요.'],
                ],
            },
            evidence: page(14),
        },
        '온라인 신청 후 제출 대상자로 뽑히면 인터넷·등기우편·방문으로 서류를 내요. 등기우편은 12월 4일 우체국 소인까지 인정해요.',
        '현장 신청은 65세 이상 등 인터넷 접수가 어려운 사람을 위한 방법이에요.',
        page(14),
    ]);
}
