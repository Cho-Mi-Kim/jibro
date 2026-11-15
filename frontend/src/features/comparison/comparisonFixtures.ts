import type { NoticeComparison } from './comparisonApi';

export function comparisonFixture(id: string): NoticeComparison {
    const evidence = {
        label: `${id} 공고문 3쪽`,
        url: `https://apply.lh.or.kr/notice/${id}`,
        checkedAt: '2026-11-06',
    };
    return {
        notice: {
            id,
            title: `${id} 청년 공고`,
            agency: 'LH',
            region: id === 'A' ? '서울' : '경기',
            type: '청년 매입임대',
            status: 'OPEN',
            publishedDate: '2026-10-01',
            startDate: '2026-10-10',
            endDate: '2026-12-31',
            summary: `${id} 지원 설명`,
            attachments: [],
            version: 3,
            guideVersion: 3,
            guideState: 'AVAILABLE',
            canStart: true,
            evidence,
            questions: [
                {
                    id: 'residence',
                    title: `${id} 거주지 기준`,
                    reason: '주민등록상 거주지를 확인하세요.',
                    evidence,
                    options: [
                        { value: 'SEOUL', label: '서울 거주' },
                        { value: 'OTHER', label: '그 외 지역' },
                    ],
                },
            ],
            documents: [`${id} 주민등록등본`, `${id} 추가 서류`],
            schedules: [
                {
                    id: 'closing',
                    title: '신청 접수 마감 (18:00, 조기 중단 가능)',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'result',
                    title: '서류 제출 대상자 발표',
                    date: '2027-01-10',
                    evidence,
                },
            ],
        },
        documentRequirements: [
            {
                id: 'common',
                title: `${id} 주민등록등본`,
                conditionQuestionId: null,
                conditionAnswerValues: null,
                method: '공고일 이후 발급한 서류',
                issuer: '정부24',
                issueUrl: 'https://www.gov.kr/',
                evidence,
            },
            {
                id: 'conditional',
                title: `${id} 추가 서류`,
                conditionQuestionId: 'residence',
                conditionAnswerValues: ['SEOUL'],
                method: '해당자만 제출',
                issuer: '주민센터',
                issueUrl: null,
                evidence,
            },
        ],
    };
}
