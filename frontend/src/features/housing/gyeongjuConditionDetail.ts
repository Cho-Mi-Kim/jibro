import { matchesSpecializedCondition } from './specializedConditionIdentity';
import type { Notice, Question } from './types';
import { questionReviewIssues } from './reviewIssueSelectors';
import { noticeConditionSentence } from './plainNoticeCondition';
import { sectioned, type DetailItem } from './structuredConditionDetail';

const pdf = 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69355347';
const sha = '020ea6f89615b24f0604af1b155f2417b01f40649a3202be6b3089e86a7c9da3';

function page(number: number) {
    return [
        {
            label: `PDF ${number}쪽 보기`,
            url: `${pdf}#page=${number}&sha256=${sha}`,
            checkedAt: '2026-11-18',
        },
    ];
}

const applicationSchedule: DetailItem[] = [
    {
        table: {
            title: '청약 접수와 순번 발표',
            headers: ['절차', '일정'],
            rows: [
                [
                    '청약 접수',
                    '11월 17일 10시부터 11월 19일 16시까지\n인터넷·모바일·현장에서 신청해요. 점심시간은 12~13시예요.',
                ],
                [
                    '순번 발표',
                    '11월 23일 14시 이후\n동·호를 고를 순번과 계약 일정을 확인해요.',
                ],
            ],
        },
        evidence: page(1),
    },
    '아래 방문일에 신청한 단지와 주택형 안에서 동·호를 골라요. 동·호 선택은 오전 10시, 계약서 작성은 오전 11시부터 오후 4시까지예요.',
    {
        table: {
            title: '단지별 동·호 선택 및 계약일',
            headers: ['단지', '방문일'],
            rows: [
                ['경주건천', '11월 24일'],
                ['경주안강', '11월 25일'],
                ['경주외동', '11월 26일'],
                ['영천망정5', '11월 27일'],
                ['잔여세대가 있을 때', '12월 1일'],
            ],
        },
        evidence: page(6),
    },
];

const submission: DetailItem[] = [
    {
        table: {
            title: '신청 방법별 서류 제출 시점',
            headers: ['신청 방법', '언제 무엇을 내나요?'],
            rows: [
                ['현장', '청약할 때 공급신청서와 동의서를 내요.'],
                ['인터넷·모바일', '동·호를 고르고 계약하는 날 동의서를 내요.'],
            ],
        },
        evidence: page(7),
    },
];

const householdRule =
    '상세 모집공고는 2026.11.05.을 자격 기준일로 정하고 입주 시까지 무주택을 유지하도록 합니다.';
const incomeRule = '소득은 도시근로자 월평균소득 150% 이하로 완화합니다.';

const reviewed: Record<
    string,
    {
        reason: string;
        sourcePage: number;
        preview?: string;
        items: DetailItem[];
    }
> = {
    household: {
        reason:
            householdRule +
            ' 미성년 세대주 예외, 분리 배우자·예비신혼 세대 범위와 불법양도·전대 제한을 확인하세요.',
        sourcePage: 4,
        items: [
            noticeConditionSentence(householdRule),
            '2026년 11월 5일부터 입주할 때까지 이 기준을 충족해야 해요.',
            {
                table: {
                    title: '함께 심사받는 가족',
                    headers: ['가족', '포함되는 경우'],
                    rows: [
                        ['신청자·배우자', '배우자는 주소가 달라도 포함돼요.'],
                        [
                            '양쪽 부모·조부모',
                            '신청자 또는 따로 사는 배우자와 같은 등본이면 포함돼요.',
                        ],
                        [
                            '신청자의 자녀·손주와 그 배우자',
                            '신청자 또는 따로 사는 배우자와 같은 등본이면 포함돼요.',
                        ],
                        [
                            '배우자의 자녀·손주와 그 배우자',
                            '신청자와 같은 등본이면 포함돼요.',
                        ],
                        [
                            '예비신혼부부',
                            '결혼 후 함께 살 가족을 기준으로 봐요. 두 사람 중 한 명을 대표 신청자로 정하면 바꿀 수 없어요.',
                        ],
                        [
                            '외국인 가족',
                            '외국인 배우자는 가족관계등록부에 올라 있고 외국인 등록 또는 국내거소신고를 했다면 주소가 달라도 포함돼요. 외국인 부모·자녀 등은 등본이나 등록된 거주지도 함께 확인해요.',
                        ],
                        [
                            '태아',
                            '가구원 수에는 포함되지만 자격검증은 제외돼요.',
                        ],
                    ],
                },
                evidence: [...page(4), ...page(5)],
            },
            '매매로 얻은 분양권·입주권은 확인하고, 상속·증여로 받은 분양권 등은 제외해요.',
            '만 19세 이상이 원칙이에요. 미성년 세대주는 다음 중 하나에 해당하면 법정대리인의 동의 또는 대리로 신청할 수 있어요.',
            '• 같은 등본에 있는 자녀를 부양해요.',
            '• 부모·조부모 등의 사망·실종·행방불명으로 같은 등본의 형제자매를 부양해요. 행방불명은 신고 접수증이 필요해요.',
            '• 외국인 부모를 둔 한국 국적의 미성년 세대주인 한부모가족이에요. 이때는 외국인 부모가 대신 신청해요.',
            page(4),
            '세대원 중 공공임대주택을 불법으로 양도하거나 다시 빌려주다 적발된 지 4년이 지나지 않은 사람이 있으면 선정될 수 없어요.',
            page(5),
        ],
    },
    income: {
        reason:
            incomeRule +
            ' 총자산 3억4,500만원·자동차 4,542만원 등 공고 기준과 자녀 가산을 확인하세요. 주택소유와 자산 기준 전체가 면제되는 공고는 아닙니다.',
        sourcePage: 5,
        items: [
            noticeConditionSentence(incomeRule),
            '세대원 전체의 세전 월평균 소득을 합산해요. 가구원 수에는 외국인 배우자와 태아도 포함해요.',
            {
                table: {
                    title: '가구원 수별 월소득 한도',
                    headers: ['가구원 수', '월소득 한도'],
                    rows: [
                        ['1인', '5,720,045원'],
                        ['2인', '8,799,405원'],
                        ['3인', '12,252,644원'],
                        ['4인', '13,203,303원'],
                        ['5인', '13,990,478원'],
                        ['6인', '14,859,395원'],
                        ['7인', '15,728,311원'],
                        ['8인', '16,597,228원'],
                    ],
                },
                evidence: page(5),
            },
            '2023년 3월 28일부터 태어난 자녀가 한 명이라도 있으면 소득·자산 한도가 올라가요. 이때는 그 전에 태어난 미성년 자녀도 함께 세요.',
            '• 신청자나 배우자의 등본에 있는 미성년 자녀를 세요. 입양 자녀와 태아도 포함해요.',
            '• 예를 들어 2021년생 첫째만 있으면 기본 한도예요. 2024년생 둘째도 있으면 두 자녀 모두 인정돼요.',
            {
                table: {
                    title: '인정되는 자녀 수에 따라 달라지는 자산 한도',
                    headers: [
                        '인정 자녀 수',
                        '가구 총자산 한도',
                        '자동차 1대당 한도',
                    ],
                    rows: [
                        ['0명\n기본 한도', '3억 4,500만 원', '4,542만 원'],
                        ['1명\n10% 가산', '3억 7,900만 원', '4,996만 원'],
                        ['2명 이상\n20% 가산', '4억 1,300만 원', '5,451만 원'],
                    ],
                },
                evidence: page(5),
            },
        ],
    },
    selection: {
        reason: '순위·배점 없이 주택형별 무작위 추첨으로 동호지정 순번을 정합니다. 계약 후 약 2~3개월 자격검증을 하며 적격자만 입주합니다. 정해진 방문시각·서류·계약금과 미방문 시 순번 소멸 기준을 확인하세요.',
        sourcePage: 1,
        items: [
            '순위나 배점 없이 신청한 주택형 안에서 무작위로 추첨해요. 뽑힌 순번대로 동·호를 선택해요. 한 세대가 중복 신청하면 모두 무효예요.',
            page(1),
            '1. 방문 전에는 서류와 계약금 50만 원을 준비해요.',
            '2. 동·호 지정일에는 오전 10시까지 도착해 명부에 서명해요.',
            '3. 내 순번이 오면 동·호를 고르고 계약을 맺어요.',
            '4. 계약 후 약 2~3개월 동안 입주 자격을 확인해요.',
            '• 자격이 맞으면 입주 안내를 받아요.',
            '• 자격이 맞지 않으면 계약이 취소되고, 낸 계약금은 이자 없이 돌려받아요.',
            '계약 후 마음이 바뀌어 직접 취소하면 위약금이 생길 수 있어요.',
            page(2),
        ],
    },
    method: {
        reason: '청약은 11/17 10시~11/19 16시(점심 제외)입니다. 서류는 동호지정·계약 시 제출하며 경주건천 11/24, 안강 11/25, 외동 11/26, 영천망정5 11/27, 잔여세대 12/1 일정이 다릅니다.',
        sourcePage: 6,
        items: [...applicationSchedule, ...submission],
    },
    'applies-application': {
        reason: '현장 청약 신청자인가요?',
        sourcePage: 7,
        items: [
            '현장에서 청약하는 사람은 LH 공급신청서를 작성해 청약할 때 내요.',
            page(7),
        ],
    },
    'applies-pledges': {
        reason: '동호지정 선정자가 되어 계약하나요?',
        sourcePage: 7,
        items: [
            noticeConditionSentence('동호지정 선정자가 되어 계약하나요?'),
            '• 입주자격 완화 확약서',
            '• 선계약 후검증 확약서',
            page(7),
        ],
    },
    'applies-family': {
        reason: '배우자 관계 확인·외국인 배우자·예비신혼부부 세대 관계 확인 등이 필요한가요?',
        sourcePage: 7,
        items: [
            '가족관계증명서(상세)를 내는 경우',
            '• 미혼',
            '• 등본에서 배우자 확인 불가(세대분리·이혼·사별 등)',
            '• 배우자가 외국인',
            '예비신혼부부가 다른 가족과 함께 살 예정이라면, 그 가족과의 관계가 나오는 신청자 또는 예비배우자의 가족관계증명서(상세)를 내요.',
            page(7),
        ],
    },
    'applies-history': {
        reason: '공고일 이후 주소·세대주 변동이 있거나 예비신혼부부 대표신청자인가요?',
        sourcePage: 7,
        preview: '공고일 이후 주소·세대주 변경자와 예비신혼 대표가 내요.',
        items: [
            '아래에 해당하면 주민등록초본을 내요.',
            '• 11월 5일 이후 주소나 세대주가 바뀐 세대원',
            '• 예비신혼부부의 대표 신청자',
            '발급할 때 과거 주소 변동 내역, 세대주 이름과 관계가 모두 나오게 선택해요.',
            page(7),
        ],
    },
    'applies-newlywed': {
        reason: '예비신혼부부로 신청하나요?',
        sourcePage: 7,
        preview: 'LH 확인서 2종과 두 사람의 등본을 준비해요.',
        items: [
            '예비신혼부부는 아래 서류를 준비해요.',
            '• 신청확인서(LH 양식)',
            '• 세대구성 확인서(LH 양식)',
            '• 두 사람의 주민등록등본 각각',
            '입주 전에는 혼인관계증명서(상세)로 신청할 때 적은 예비배우자와 결혼했는지 확인해요.',
            page(7),
        ],
    },
    'applies-foreign': {
        reason: '배우자가 외국인인가요?',
        sourcePage: 7,
        preview: '외국인등록사실증명서와 상세 가족관계증명서를 내요.',
        items: [
            '배우자가 외국인이면 아래 서류를 준비해요.',
            '• 외국인등록사실증명서',
            '• 가족관계증명서(상세)',
            page(7),
        ],
    },
    'applies-identity': {
        reason: '본인 또는 대리인이 현장 신청하나요?',
        sourcePage: 7,
        preview: '신분증을 챙기고, 대리 신청은 추가 서류도 준비해요.',
        items: [
            '동·호를 고르거나 계약하러 갈 때는 신분증을 가져가요.',
            page(2),
            '배우자가 대신 신청할 때는 아래 서류를 준비해요.',
            '• 신청자와 배우자의 신분증',
            '• 신청자의 도장',
            '• 두 사람의 관계를 증명하는 서류',
            '배우자 외의 사람이 대신 신청할 때는 신청자와 대리인의 신분증과 함께 아래 두 가지 중 한 묶음을 준비해요.',
            '• 인감증명서 + 인감도장 + 위임장',
            '• 본인서명사실확인서 + 자필 위임장',
            page(7),
        ],
    },
    'applies-pregnancy': {
        reason: '태아·입양자녀를 가구원수나 소득·자산 가산에 포함하려 하나요?',
        sourcePage: 7,
        preview: '임신은 진단서나 확인서, 입양은 입양관계증명서를 내요.',
        items: [
            '• 임신: 공고일 이후 병원 등 의료기관에서 발급한 임신진단서 또는 임신확인서',
            '• 입양: 행정복지센터에서 발급한 입양관계증명서',
            page(7),
        ],
    },
};

function reviewedEntry(question: Question) {
    const entry = reviewed[question.id];
    if (
        !entry ||
        !matchesSpecializedCondition(question, 'gyeongju') ||
        question.reason !== entry.reason
    )
        return;
    return entry;
}

export function gyeongjuConditionDetail(question: Question) {
    const entry = reviewedEntry(question);
    return entry && { ...sectioned(entry.items), preview: entry.preview };
}

export function gyeongjuSummaryCondition(
    notice: Notice,
    id: 'method' | 'submission' | 'household' | 'income' | 'selection',
) {
    if (notice.guideState !== 'AVAILABLE') return;
    const q = notice.questions.find(
        (question) => question.id === (id === 'submission' ? 'method' : id),
    );
    if (
        !q ||
        q.reviewRequired ||
        questionReviewIssues(notice.reviewIssues, q.id).length
    )
        return;
    if (
        id === 'method' &&
        notice.reviewIssues?.some((issue) => issue.area === 'SCHEDULE')
    )
        return;
    const entry = reviewedEntry(q);
    if (!entry) return;
    if (id === 'submission') return sectioned(submission);
    if (id === 'method') return sectioned(applicationSchedule);
    return sectioned(entry.items);
}
