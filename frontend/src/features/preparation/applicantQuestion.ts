import type { AnswerOption, Question } from '../housing/types';
import {
    answerOptions,
    isAcknowledgementQuestion,
    questionKind,
} from '../housing/questions';
import {
    pohangApplicantQuestion,
    type ApplicantQuestionContext,
} from './pohangApplicantQuestion';
export { updateApplicantAnswer } from './pohangApplicantQuestion';
export type { ApplicantQuestionContext } from './pohangApplicantQuestion';

export type ApplicantQuestion = {
    title: string;
    help: string;
    options: AnswerOption[];
    needsCriteria: boolean;
    preview?: string;
};

/** Input prompts describe the applicant's answer, independently of information-card headings. */
export function applicantQuestion(
    question: Question,
    context?: ApplicantQuestionContext,
): ApplicantQuestion {
    const contextual = pohangApplicantQuestion(question, context);
    if (contextual) return contextual;
    const options = answerOptions(question);
    const kind = questionKind(question);
    const title = question.title.trim();
    const values = new Set(options.map((option) => option.value));
    const labels = options.map((option) => option.label).join(' ');
    const prompt = (
        nextTitle: string,
        help: string,
        needsCriteria = false,
    ) => ({
        title: nextTitle,
        help,
        options,
        needsCriteria,
    });
    const selectionHelp =
        kind === 'MULTIPLE'
            ? '내 상황에 해당하는 항목을 모두 골라 주세요.'
            : '내 상황에 맞는 항목을 하나 골라 주세요.';

    if (kind === 'NUMBER' || kind === 'DATE')
        return prompt(
            title,
            kind === 'NUMBER'
                ? '안내된 기준일과 단위를 확인하고 내 정보를 입력해 주세요.'
                : '어떤 날짜를 묻는지 확인하고 해당 날짜를 입력해 주세요.',
            true,
        );

    if (isAcknowledgementQuestion(question))
        return prompt(
            title.endsWith('?') ? title : `${title} 안내를 확인했나요?`,
            '다음 준비에 필요한 안내예요. 내용을 읽고 확인한 상태를 골라 주세요.',
            true,
        );

    // Reviewed branches already ask about one fixed household or applicant scope.
    if (question.applicability) return prompt(title, selectionHelp, true);

    // Generic yes/no and review answers can cover several rules and exceptions.
    // Only explicit factual choices support a more specific applicant question.
    if (!question.reviewRequired && question.options?.length) {
        if (
            /유형|계층|대상/.test(title) &&
            [
                'YOUTH',
                'EARLY_CAREER',
                'NEWLYWED',
                'PREMARITAL',
                'SINGLE_PARENT',
                'ELDER',
                'INDUSTRIAL',
                'INTEGRATED',
            ].filter((value) => values.has(value)).length >= 2 &&
            /청년|신혼|한부모|고령|산업단지/.test(labels)
        )
            return prompt('어떤 유형으로 신청할 예정인가요?', selectionHelp);

        if (
            /유형|일반공급|주거약자/.test(title) &&
            values.has('GENERAL') &&
            /일반공급/.test(labels) &&
            /주거약자/.test(labels)
        )
            return prompt(
                '일반공급과 주거약자형 중 어디에 신청하나요?',
                selectionHelp,
            );

        if (
            /신청|청약|접수/.test(title) &&
            values.has('ONLINE') &&
            /인터넷|온라인|모바일/.test(labels)
        )
            return prompt('어떤 방법으로 신청할 예정인가요?', selectionHelp);

        if (
            /신청|대리|방문/.test(title) &&
            values.has('SELF') &&
            values.has('SPOUSE') &&
            values.has('OTHER') &&
            /대리|대신/.test(labels)
        )
            return prompt('누가 방문해서 신청하나요?', selectionHelp);

        if (
            /가족|배우자/.test(title) &&
            values.has('NO_SPOUSE') &&
            values.has('SEPARATE_SPOUSE') &&
            values.has('FOREIGN_SPOUSE') &&
            /미혼|이혼|사별/.test(labels)
        )
            return prompt(
                '배우자·가족 관계에서 내 상황은 무엇인가요?',
                selectionHelp,
                true,
            );

        if (
            /임신|입양|태아|자녀/.test(title) &&
            values.has('PREGNANT') &&
            values.has('ADOPTED') &&
            /태아|임신/.test(labels) &&
            /입양/.test(labels)
        )
            return prompt(
                values.has('BIRTH')
                    ? '함께 확인할 자녀가 있나요?'
                    : '태아나 입양 자녀를 포함해 신청하나요?',
                selectionHelp,
                true,
            );

        if (
            /나이|성년|미성년/.test(title) &&
            values.has('ADULT') &&
            [...values].some((value) => value.startsWith('MINOR_')) &&
            /미성년/.test(labels)
        )
            return prompt(
                '내 나이와 상황은 어디에 해당하나요?',
                '공고에서 정한 기준일과 미성년자 예외를 함께 확인해 주세요.',
                true,
            );

        if (values.has('MEETS') && values.has('DOES_NOT_MEET')) {
            if (/소득|자산|재산/.test(title)) {
                const scope = /소득/.test(title)
                    ? /자산|재산/.test(title)
                        ? '소득·재산'
                        : '소득'
                    : '재산';
                return prompt(
                    `내 신청 유형의 ${scope} 기준에 맞나요?`,
                    '내 신청 유형에 적용되는 기준과 예외를 함께 확인해 주세요.',
                    true,
                );
            }
            if (/주택|무주택|집이/.test(title))
                return prompt(
                    '내 신청 유형의 주택 보유 기준에 맞나요?',
                    '신청자와 함께 심사받는 가족의 기준을 확인해 주세요.',
                    true,
                );
            if (/산업단지|근로/.test(title))
                return prompt(
                    '산업단지 근로자 기준에 해당하나요?',
                    selectionHelp,
                    true,
                );
        }

        if (/제출 대상/.test(title)) {
            const factualQuestion = question.reason
                .trim()
                .match(/^([^?\n]{4,100}\?)(?:\s|$)/)?.[1];
            if (factualQuestion)
                return prompt(
                    factualQuestion,
                    '서류를 이미 준비했는지가 아니라, 제출 대상에 해당하는지 골라 주세요.',
                    true,
                );
            return prompt(
                title,
                '아래 제출 기준을 보고 내 상황에 해당하는지 골라 주세요. 서류 준비 여부는 나중에 표시해요.',
                true,
            );
        }
    }

    return prompt(
        title.endsWith('?') ? title : `${title} · 내 상황에 해당하나요?`,
        '내 상황과 아래 기준·예외를 함께 확인해 주세요.',
        true,
    );
}
