import { safeUrl } from '../../shared/ui/helpers';

export interface IssuanceLink {
    label: string;
    url: string;
    note?: string;
}

type DocumentSource = {
    title: string;
    issuer: string;
    issueLabel?: string | null;
    issueUrl?: string | null;
    method?: string;
};

type IssuanceRule = IssuanceLink & { name: RegExp };

function government(
    name: RegExp,
    label: string,
    service: string,
    note?: string,
): IssuanceRule {
    return {
        name,
        label: '정부24 ' + label,
        url: 'https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=' + service,
        note,
    };
}

// Each pattern identifies a specific certificate, including named parts of a bundle.
const rules: IssuanceRule[] = [
    government(
        /주민등록(?:표)?(?:등본|초본|등[·ㆍ/()및]*초본)|세대주(?:의)?등본|세대원의초본|자녀등재등본/,
        '주민등록표 발급',
        '13100000015',
        '공고에서 지정한 사람을 기준으로 주소 이력과 표시 항목을 선택해 주세요. 다른 세대의 배우자는 본인이 신청해 주세요.',
    ),
    {
        name: /(?:가족|혼인|입양)관계증명서?|기본증명서|자녀기본·가족관계증명/,
        label: '대법원 가족관계등록 증명서 발급',
        url: 'https://efamily.scourt.go.kr/',
        note: '공고에 적힌 증명서 종류와 발급 대상자, 일반·상세 구분을 선택해 주세요.',
    },
    government(
        /^(?:(?:국민)?기초생활|(?:생계·의료(?:·주거)?|주거)급여|급여)?수급자증명서|^수급자·차상위증명서$|수급·차상위증빙$/,
        '국민기초생활수급자 증명서 발급',
        '14600000280',
    ),
    government(
        /차상위계층(?:확인(?:서|서류)|(?:자격)?증명|입증서류|증빙)|(?:수급자?|수급권자)·차상위(?:증명|증빙)/,
        '차상위계층 확인서 발급',
        '13520000098',
        '차상위계층 확인 자격에 해당할 때 이용하세요. 다른 차상위 자격은 공고에 지정된 증빙을 확인해 주세요.',
    ),
    government(/한부모가족증명서/, '한부모가족 증명서 발급', '10601000001'),
    government(
        /(?<!법인)(?<!법인용)인감증명서|^인감증명방식위임서류$/,
        '인감증명서 발급 안내',
        '13100000025',
        '온라인 발급은 본인만 가능해요. 공고의 제출 용도에 쓸 수 있는지 확인하고 위임장 등은 별도로 준비해 주세요.',
    ),
    government(
        /(?<!전자)본인서명사실확인서|^본인서명방식위임서류$/,
        '본인서명사실확인서 방문 발급 안내',
        '13110000047',
        '신청자 본인이 행정복지센터를 방문해 발급받아요.',
    ),
    government(
        /외국인등록사실증명|외국인등록·국내거소신고사실증명/,
        '외국인등록 사실증명 발급',
        '12700000027',
    ),
    government(
        /국내거소신고사실증명/,
        '국내거소신고 사실증명 발급',
        '12700000091',
    ),
    government(
        /북한이탈주민등록확인서/,
        '북한이탈주민등록확인서 발급',
        '12500000065',
    ),
    government(/(?<!중증)장애인증명서/, '장애인증명서 발급', '14600000273'),
    {
        name: /중증장애인확인서/,
        label: '한국장애인고용공단 중증장애인 확인서 발급',
        url: 'https://hub.kead.or.kr/ve/myPgeSrsillDspsnCnfrmnIssuReqstIndvdlinfoProvdGuidance.do',
    },
    government(
        /국가유공자(?:\(유족또는가족\)|·유족등|등)?확인서?/,
        '국가유공자·유족 확인 안내',
        '11800000014',
        '공고에서 별도로 요구하는 영구임대 지원대상 증명은 보훈 관서에서 확인해 주세요.',
    ),
    government(
        /건강보험자격득실확인서/,
        '건강보험 자격득실확인서 발급',
        'PG4CADM0107',
    ),
    government(
        /국민연금가입(?:자|자가입)?증명서/,
        '국민연금 가입자 증명서 발급',
        '14600000312',
    ),
    government(/병적증명서/, '병적증명서 발급', '13000000016'),
    government(/사업자등록증명/, '사업자등록증명 발급', '12100000016'),
    government(
        /휴학증명서/,
        '대학교 휴학증명 신청 안내',
        '13404000011',
        '대학 휴학을 증명하는 서류예요. 복학 각서는 공고의 양식을 확인해 주세요.',
    ),
    government(/소득금액증명(?:원|서)?/, '소득금액증명 발급', '12100000021'),
    government(
        /대학(?:교)?재학증명서/,
        '대학교 재학증명 신청 안내',
        '13404000010',
        '정부24 신청 후 지정 기관에서 수령해요. 온라인 출력은 소속 대학에서 확인해 주세요.',
    ),
    government(
        /(?:유치원|초등학교|중학교|고등학교)재학증명서/,
        '유·초·중·고 재학증명 신청 안내',
        '13410000017',
    ),
    government(
        /대학(?:교)?졸업(?:\(예정\))?증명서/,
        '대학교 졸업증명 신청 안내',
        '13404000009',
        '정부24에서 신청한 뒤 지정한 기관에서 수령해요. 온라인 출력은 소속 대학의 증명서 발급 메뉴를 확인해 주세요.',
    ),
    {
        name: /청약(?:통장|저축)순위(?:\(가입\))?확인서/,
        label: '청약홈 청약통장 순위확인서 발급',
        url: 'https://www.applyhome.co.kr/',
        note: '청약자격확인에서 청약통장 순위확인서를 선택해 주세요.',
    },
    {
        name: /여권정보증명서/,
        label: '외교부 여권정보증명서 발급 안내',
        url: 'https://www.passport.go.kr/home/kor/contents.do?menuPos=53',
    },
    {
        name: /예술활동증명/,
        label: '한국예술인복지재단 예술활동증명 안내',
        url: 'https://www.kawfartist.kr/views/cms/hkor/cs/cs01006_/cs01006_01.jsp',
    },
];

const bundles = [
    {
        subject: /연금|건강보험|소득활동|근로증빙/,
        certificates:
            /국민연금가입(?:자)?증명서|건강보험자격득실확인서|사업자등록증명/g,
    },
    { subject: /병역/, certificates: /병적증명서/g },
    { subject: /대학생증빙/, certificates: /재학증명서/g },
];

export function onlineIssuanceLinks(document: DocumentSource): IssuanceLink[] {
    if (safeUrl(document.issueUrl)) {
        return [
            {
                label: document.issueLabel || document.issuer || '공식 발급 경로',
                url: document.issueUrl,
            },
        ];
    }
    const title = document.title.normalize('NFKC').replace(/\s+/g, '');
    let name = title;
    if (
        /미제출|미발급|제출불필요|제출생략|발급불가|진위확인|제출면제|발급신청서|발급위임장/.test(
            name,
        )
    )
        return [];
    // A bare school certificate has distinct services by school level.
    if (
        /^(재학|졸업)증명서$/.test(name) &&
        /대학/.test(document.method ?? '')
    ) {
        name = '대학' + name;
    }
    // Only consult instructions for a bundle of the corresponding subject.
    // A shared general instruction can mention certificates for unrelated rows.
    const method = (document.method ?? '').replace(/\s+/g, '');
    for (const { subject, certificates } of bundles) {
        if (!subject.test(title)) continue;
        for (const certificate of method.match(certificates) ?? []) {
            name +=
                '·' +
                (certificate === '재학증명서' ? '대학재학증명서' : certificate);
        }
    }
    return rules
        .filter((rule) => rule.name.test(name))
        .map(({ label, url, note }) => ({ label, url, note }));
}
