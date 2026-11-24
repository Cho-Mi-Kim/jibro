import { mapExplanationText, noticeSummaryText } from './summaryTone';

/** Shorten our explanation, keeping every threshold, restriction and exception. */
export function conciseNoticeText(text: string): string {
    return noticeSummaryText(
        mapExplanationText(text, (part) =>
            part
                .replace(/^지원 대상\s*[:：]\s*/u, '')
                .replace(/공고에서 정한 /gu, '공고의 ')
                .replace(/공고의 (?=(?:완화된 )?(?:소득|무주택|자산))/gu, '')
                .replace(/(?:입주자격 )?기준을 충족하는/gu, '기준에 맞는')
                .replace(/요건을 충족하는/gu, '요건에 맞는')
                .replace(/입주자격을 충족하는/gu, '입주 자격을 갖춘')
                .replace(
                    /주민등록표등본에서 배우자 확인이 불가한 경우/gu,
                    '등본에서 배우자를 확인할 수 없는 경우',
                )
                .replace(
                    /혼인으로 구성될 세대에 신청자와 예비배우자 외의 사람을 포함하는 경우/gu,
                    '결혼 후 함께 살 세대에 두 사람 외의 가족을 포함하면',
                )
                .replace(/혼인으로 구성될 세대/gu, '결혼 후 함께 살 세대')
                .replace(
                    /부모·자녀간 육아지원세대 배점을 받고자 할 경우/gu,
                    '부모·자녀 육아지원 가점을 받는 경우',
                )
                .replace(
                    /사회취약계층 가산점을 받고자 하는 신청자 중/gu,
                    '사회취약계층 가점을 받으려는 신청자 중',
                )
                .replace(
                    /해당 세대원과의 관계를 확인할 수 있는/gu,
                    '그 가족과의 관계가 나오는',
                )
                .replace(/주민등록표등본상/gu, '등본상')
                .replace(/확인이 불가한/gu, '확인할 수 없는')
                .replace(/기재하고/gu, '적고')
                .replace(/기재합니다/gu, '적어요')
                .replace(/에 거주하는/gu, '에 사는')
                .replace(/에 거주하며/gu, '에 살며')
                .replace(/시중 시세/gu, '시세')
                .replace(
                    /보증금과 월 임대료를 내고 거주할 수 있도록/gu,
                    '보증금·월 임대료로',
                )
                .replace(
                    /보증금과 월 임대료를 내고 거주할/gu,
                    '보증금·월 임대료를 내는',
                )
                .replace(/보증금과 월 임대료/gu, '보증금·월 임대료')
                .replace(/해당 유형의/gu, '유형별')
                .replace(/해당 계층과 주택형에 정해진/gu, '계층·주택형별')
                .replace(/자격 유형별/gu, '유형별')
                .replace(
                    /입주자로 선정되면 지원 한도 안에서 원하는 전세주택을 찾고, LH가 집주인과 (?:전세계약을 맺어|계약한 뒤) 다시 임대해 (?:줍니다|줘요)\./gu,
                    '선정되면 지원 한도 안에서 고른 전세주택을 LH가 계약해 다시 임대해요.',
                )
                .replace(
                    /선정된 가구가 지원 한도 안에서 전세주택을 찾으면 LH가 집주인과 전세계약을 맺고 다시 임대해 (?:줍니다|줘요)\./gu,
                    '선정되면 지원 한도 안에서 고른 전세주택을 LH가 계약해 다시 임대해요.',
                )
                .replace(
                    /예비입주자로 선정되면 빈집이 생겼을 때 대기순번에 따라 계약을 안내(?:받습니다|받아요)\./gu,
                    '예비입주자는 빈집이 생기면 순번대로 계약을 안내받아요.',
                )
                .replace(
                    /빈집이 생겼을 때 대기순번에 따라/gu,
                    '빈집이 생기면 순번대로',
                )
                .replace(/대기순번에 따라/gu, '대기순번대로')
                .replace(/신청하는 경우에 한하여/gu, '신청하는 경우에만')
                .replace(/에 해당하는 경우에는/gu, '에 해당하면')
                .replace(/에 해당하는 경우(?=[,，.\s]|$)/gu, '에 해당하면')
                .replace(/제출하여야 합니다/gu, '내야 해요')
                .replace(/확인하여야 합니다/gu, '확인해야 해요')
                .replace(/발급받아 제출합니다/gu, '발급받아 내요')
                .replace(/인정되는 경우에만/gu, '인정될 때만'),
        ),
    );
}

export function summaryNarrationSentences(text: string): string[] {
    // Preserve dates, decimal amounts, numbered clauses and quoted sentences.
    if (/["'“”‘’「『`]/u.test(text)) return [text.trim()].filter(Boolean);
    return text
        .trim()
        .split(/(?<=[^\s][다요][.!?])\s+|\n+/u)
        .filter(Boolean);
}

export function distinctSummarySentences(text: string): string[] {
    return [...new Set(summaryNarrationSentences(text).map(conciseNoticeText))];
}
