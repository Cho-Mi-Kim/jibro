import vocabulary from '../../../../data/contracts/preparation-audience-vocabulary.json';
import {
    answerConflictMessages,
    dependentDocumentNecessity,
} from './answerDependencies';
import { validAnswer } from './questionAnswerInput';
import { isAcknowledgementQuestion } from './questions';
import { areaReviewIssues, documentReviewIssues } from './reviewIssueSelectors';
import type {
    Answer,
    DocumentItem,
    Notice,
    Preparation,
    Question,
} from './types';

type QuestionScope = Pick<Preparation, 'questions' | 'answers'> &
    Partial<Pick<Preparation, 'guideState' | 'reviewIssues'>>;
export type PreparationScope = QuestionScope &
    Pick<Preparation, 'documents'> &
    Partial<Pick<Preparation, 'documentConditions'>>;

const types: Readonly<Record<string, readonly string[]>> = vocabulary.types;
const channels: Readonly<Record<string, readonly string[]>> =
    vocabulary.channels;
const channelValues: Readonly<Record<string, string>> =
    vocabulary.channelValues;

const available = (p: QuestionScope) =>
    p.guideState == null || p.guideState === 'AVAILABLE';

/** Merge current public review protection after the caller checks notice identity and version. */
export function reviewedPreparationScope<T extends PreparationScope>(
    preparation: T,
    notice: Pick<Notice, 'questions' | 'documentTemplates' | 'reviewIssues'>,
): T {
    const publicQuestions = new Map(notice.questions.map((q) => [q.id, q]));
    const documentConditions = {
        ...preparation.documentConditions,
        ...Object.fromEntries(
            (notice.documentTemplates ?? []).flatMap((document) =>
                document.conditionQuestionId
                    ? [[document.id, document.conditionQuestionId]]
                    : [],
            ),
        ),
    };
    return {
        ...preparation,
        documentConditions,
        reviewIssues: [
            ...(notice.reviewIssues ?? []),
            ...(preparation.reviewIssues ?? []),
        ],
        questions: preparation.questions.map((question) =>
            publicQuestions.get(question.id)?.reviewRequired
                ? { ...question, reviewRequired: true }
                : question,
        ),
        documents: preparation.documents.map((document) =>
            requiresDocumentReview(document, preparation) ||
            publicQuestions.get(documentConditions[document.id])?.reviewRequired
                ? { ...document, reviewRequired: true }
                : document,
        ),
    };
}

function requiresDocumentReview(document: DocumentItem, p: PreparationScope) {
    const questionId = p.documentConditions?.[document.id];
    return (
        document.reviewRequired ||
        areaReviewIssues(p.reviewIssues, 'DOCUMENT').length > 0 ||
        documentReviewIssues(p.reviewIssues, document.id, questionId).length >
            0 ||
        p.questions.some(
            (question) => question.id === questionId && question.reviewRequired,
        )
    );
}

function excludedDocument(
    document: DocumentItem,
    p: PreparationScope,
    uncertain: ReadonlySet<string>,
    selected: ReturnType<typeof selectedTypes>,
) {
    const question = p.questions.find(
        (q) => q.id === p.documentConditions?.[document.id],
    );
    if (
        requiresDocumentReview(document, p) ||
        (question && uncertain.has(question.id)) ||
        dependentDocumentNecessity(
            'NOT_APPLICABLE',
            document.id,
            p.questions,
            p.answers,
        ) !== 'NOT_APPLICABLE'
    )
        return false;
    return (
        document.necessity === 'NOT_APPLICABLE' ||
        (available(p) &&
            !!question &&
            !matchesQuestionScope(question, p, uncertain, selected))
    );
}

/** Keep common, relevant, and unresolved documents without changing their saved state. */
export function visiblePreparationDocuments(p: PreparationScope) {
    const uncertain = uncertainQuestions(p);
    const selected = selectedTypes(p, uncertain);
    return p.documents.filter(
        (document) => !excludedDocument(document, p, uncertain, selected),
    );
}

function uncertainQuestions(p: QuestionScope) {
    const result = new Set(
        (p.reviewIssues ?? []).flatMap((issue) => issue.questionIds),
    );
    if (
        p.reviewIssues?.some(
            (issue) => issue.area === 'CONDITION' && !issue.questionIds.length,
        )
    )
        for (const question of p.questions) result.add(question.id);
    for (const question of p.questions) {
        if (question.reviewRequired) result.add(question.id);
        if (
            answerConflictMessages(
                question,
                p.answers[question.id],
                p.answers,
                p.questions,
            ).length
        ) {
            result.add(question.id);
            for (const dependency of question.answerDependencies ?? [])
                result.add(dependency.questionId);
        }
    }
    for (const question of p.questions)
        if (
            question.applicability &&
            result.has(question.applicability.questionId)
        )
            result.add(question.id);
    return result;
}

function explicitUnknownOrInvalid(q: Question, answer?: Answer) {
    return (
        answer !== undefined &&
        (answer === 'UNKNOWN' || !validAnswer(q, answer))
    );
}

function documentQuestion(question: Question) {
    return /제출.?대상|(?:서류|증명서|동의서|확약서).*(?:누가|내나요|준비|확인)/.test(
        question.title,
    );
}

function selectedValues(
    q: Question,
    answer: Answer | undefined,
    uncertain: ReadonlySet<string>,
) {
    if (
        answer === undefined ||
        uncertain.has(q.id) ||
        explicitUnknownOrInvalid(q, answer)
    )
        return null;
    const values =
        typeof answer === 'string'
            ? [answer]
            : Array.isArray(answer)
              ? answer
              : [];
    if (
        !values.length ||
        q.options?.some(
            (option) =>
                values.includes(option.value) && option.requiresFollowUp,
        )
    )
        return null;
    return new Set(values);
}

function namedTypes(title: string) {
    const result = new Set<string>();
    for (const [value, aliases] of Object.entries(types)) {
        if (
            aliases.some((alias) =>
                new RegExp(
                    `(?:^|[^가-힣A-Za-z0-9])${alias.replaceAll(' ', '\\s*')}(?=$|[^가-힣A-Za-z0-9]|은|는|이|가|의|으로|로|에게|만|과|와)`,
                ).test(title),
            )
        )
            result.add(value);
    }
    return result;
}

function exclusiveNamedTypes(title: string) {
    if (/아니|않|제외|달라|비교|차이|구분/.test(title))
        return new Set<string>();
    const aliases = Object.values(types)
        .flat()
        .sort((a, b) => b.length - a.length)
        .map((value) => value.replaceAll(' ', '\\s*'))
        .join('|');
    const prefix = title.match(
        new RegExp(
            `^(?:${aliases})(?:(?:[·ㆍ,/]|\\s*(?:및|또는|과|와)\\s*)(?:${aliases}))*(?:은|는|이|가|의|으로|로)?\\s+`,
        ),
    );
    return prefix ? namedTypes(prefix[0]) : new Set<string>();
}

function optionTypes(option: NonNullable<Question['options']>[number]) {
    if (
        option.requiresFollowUp ||
        ['UNKNOWN', 'NONE', 'OTHER', 'NOT_APPLICABLE'].includes(option.value)
    )
        return new Set<string>();
    const named = namedTypes(option.label);
    if (named.size) return named;
    const rule = vocabulary.typeOptionRules.find(
        (rule) =>
            rule.values.includes(option.value) &&
            new RegExp(rule.label).test(option.label),
    );
    return new Set(rule ? [rule.type] : []);
}

function typeSelector(q: Question) {
    return (
        !!q.options &&
        /(?:신청|공급).*(?:유형|계층|경로)|(?:유형|계층|경로).*(?:신청|공급)|어떤\s*유형|일반공급.*주거약자/.test(
            q.title,
        ) &&
        !/가점|순위|인정\s*유형/.test(q.title) &&
        new Set(q.options.flatMap((option) => [...optionTypes(option)])).size >=
            2
    );
}

function typeParent(p: QuestionScope) {
    const parents = p.questions.filter(typeSelector);
    return parents.length === 1 ? parents[0] : null;
}

function selectedTypes(p: QuestionScope, uncertain: ReadonlySet<string>) {
    const question = typeParent(p);
    if (!question) return null;
    const answers = selectedValues(question, p.answers[question.id], uncertain);
    if (!answers) return null;
    const choices = new Map(
        question.options?.map((option) => [option.value, optionTypes(option)]),
    );
    if ([...answers].some((answer) => !choices.get(answer)?.size)) return null;
    return {
        question,
        choices,
        values: new Set(
            [...answers].flatMap((answer) => [...choices.get(answer)!]),
        ),
    };
}

function matchesQuestionScope(
    question: Question,
    p: QuestionScope,
    uncertain: ReadonlySet<string>,
    selected: ReturnType<typeof selectedTypes>,
) {
    if (question.applicability) {
        const parent = p.questions.find(
            (q) => q.id === question.applicability!.questionId,
        );
        if (!parent) return true;
        const values = selectedValues(parent, p.answers[parent.id], uncertain);
        return (
            !values ||
            question.applicability.answerValues.some((value) =>
                values.has(value),
            )
        );
    }
    if (!selected || question.id === selected.question.id) return true;
    const scope = exclusiveNamedTypes(question.title);
    if (!scope.size) return true;
    // Explicit dependency values only widen an already named audience; they never establish exclusivity.
    for (const dependency of question.answerDependencies ?? []) {
        if (
            dependency.questionId !== selected.question.id ||
            !dependency.conflictingValues.includes('NOT_APPLICABLE')
        )
            continue;
        for (const value of dependency.answerValues) {
            const types = selected.choices.get(value);
            if (!types?.size) return true;
            for (const type of types) scope.add(type);
        }
    }
    const overlaps: Readonly<Record<string, readonly string[]>> =
        vocabulary.typeOverlaps;
    const choices = new Set(
        [...selected.choices.values()].flatMap((types) => [...types]),
    );
    if ([...scope].some((type) => !choices.has(type))) return true;
    for (const type of [...scope])
        for (const overlap of overlaps[type] ?? []) scope.add(overlap);
    return [...scope].some((type) => selected.values.has(type));
}

/** Input branches depend on the selected application type, never on their own answer or document state. */
export function applicantInputQuestions(p: QuestionScope) {
    if (!available(p)) return p.questions;
    const uncertain = uncertainQuestions(p);
    const selected = selectedTypes(p, uncertain);
    const questions = p.questions.filter(
        (question) =>
            uncertain.has(question.id) ||
            matchesQuestionScope(question, p, uncertain, selected),
    );
    const parentIds = new Set(
        p.questions.flatMap((q) =>
            q.applicability ? [q.applicability.questionId] : [],
        ),
    );
    const inferred = typeParent(p);
    if (inferred) parentIds.add(inferred.id);
    return [
        ...questions.filter((q) => parentIds.has(q.id)),
        ...questions.filter((q) => !parentIds.has(q.id)),
    ];
}

function selectedChannels(p: PreparationScope, uncertain: ReadonlySet<string>) {
    const parents = p.questions.filter(
        (q) =>
            q.options &&
            /신청|접수|청약/.test(q.title) &&
            /방법|방식/.test(q.title) &&
            q.options.some((option) => !!channelValues[option.value]),
    );
    if (parents.length !== 1) return null;
    const q = parents[0];
    const values = selectedValues(q, p.answers[q.id], uncertain);
    if (!values || [...values].some((value) => !channelValues[value]))
        return null;
    const result = new Set<string>();
    for (const option of q.options ?? []) {
        if (!values.has(option.value)) continue;
        const channel = channelValues[option.value];
        if (
            /불가|제외|않|아니|불허/.test(option.label) ||
            !channels[channel].some((alias) => option.label.includes(alias)) ||
            Object.values(channels).filter((aliases) =>
                aliases.some((alias) => option.label.includes(alias)),
            ).length !== 1
        )
            return null;
        result.add(channel);
    }
    return result;
}

/** Preserve originals and unresolved items; this selects a display and checklist scope. */
export function visiblePreparationQuestions(p: PreparationScope) {
    if (!available(p)) return p.questions;
    const uncertain = uncertainQuestions(p);
    const selected = selectedTypes(p, uncertain);
    return p.questions.filter((question) => {
        if (uncertain.has(question.id)) return true;
        if (!matchesQuestionScope(question, p, uncertain, selected))
            return false;
        const answer = p.answers[question.id];
        if (
            explicitUnknownOrInvalid(question, answer) ||
            question.options?.some(
                (option) =>
                    option.requiresFollowUp &&
                    (Array.isArray(answer)
                        ? answer.includes(option.value)
                        : answer === option.value),
            ) ||
            answer === 'DOES_NOT_MEET' ||
            (answer === 'NO' && !documentQuestion(question))
        )
            return true;
        if (
            typeof answer === 'string' &&
            ['NOT_MY_TYPE', 'NOT_APPLICABLE', 'NOT_REQUIRED'].includes(
                answer,
            ) &&
            question.options?.some(
                (option) =>
                    option.value === answer &&
                    /다른.?유형|해당.?없|해당하지.?않|제출.?대상.{0,5}아니/.test(
                        option.label,
                    ),
            )
        )
            return false;
        if (isAcknowledgementQuestion(question)) {
            const guidance = vocabulary.documentGuidance.find(
                (item) => item.title === question.title,
            );
            if (guidance) {
                const linked = p.documents.filter((document) =>
                    guidance.documents.includes(document.title),
                );
                if (
                    new Set(linked.map((document) => document.title)).size ===
                        guidance.documents.length &&
                    linked.every(
                        (document) =>
                            excludedDocument(
                                document,
                                p,
                                uncertain,
                                selected,
                            ) &&
                            !uncertain.has(
                                p.documentConditions?.[document.id] ?? '',
                            ),
                    )
                )
                    return false;
            }
        }
        if (documentQuestion(question) && answer !== undefined) {
            const linked = p.documents.filter(
                (document) =>
                    p.documentConditions?.[document.id] === question.id,
            );
            if (
                linked.length &&
                linked.every((document) =>
                    excludedDocument(document, p, uncertain, selected),
                )
            )
                return false;
        }
        return true;
    });
}

export function preparationScheduleSelection<T extends { title: string }>(
    p: PreparationScope,
    schedules: T[],
) {
    if (
        !available(p) ||
        p.reviewIssues?.some((issue) => issue.area === 'SCHEDULE')
    )
        return { visible: schedules, selected: [] as T[] };
    const selected = selectedChannels(p, uncertainQuestions(p));
    if (!selected) return { visible: schedules, selected: [] as T[] };
    const matching: T[] = [];
    const visible = schedules.filter((schedule) => {
        const title = schedule.title;
        if (
            !title.startsWith('접수 회차 · ') ||
            /동.?호|계약|서류|증빙|제출|불가|제외|않|아니|불허/.test(title)
        )
            return true;
        const mentioned = Object.values(channels).filter((aliases) =>
            aliases.some((alias) =>
                new RegExp(
                    `(?:^|[^가-힣A-Za-z0-9])${alias}(?=$|[^가-힣A-Za-z0-9]|접수|신청|청약)`,
                ).test(title),
            ),
        ).length;
        if (mentioned > 1) return true;
        const scope = new Set<string>();
        for (const [channel, aliases] of Object.entries(channels)) {
            if (
                aliases.some((alias) =>
                    new RegExp(
                        `(?:^|[^가-힣A-Za-z0-9])${alias}\\s*(?:접수|신청|청약)`,
                    ).test(title),
                )
            )
                scope.add(channel);
        }
        if (!scope.size) return true;
        const matches = [...scope].some((channel) => selected.has(channel));
        if (matches) matching.push(schedule);
        return matches;
    });
    return { visible, selected: matching };
}
