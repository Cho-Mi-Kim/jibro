import { supportSummaryRows } from './supportSummaryRows';
import type { Notice } from './types';
import { samryeQualificationSummary } from './samryeNoticeSummary';
import { noticeSummaryText } from './summaryTone';
import { conciseNoticeText } from './conciseNoticeText';
import { qualificationTables } from './parseQualificationTables';
import { gyeongjuSummaryCondition } from './gyeongjuConditionDetail';
import { reviewedSummaryDetail } from './conditionOverview';
import type { StructuredConditionDetail } from './structuredConditionDetail';

/** Resolve reviewed rows, details and evidence together for every qualification view. */
export function qualificationSummary(notice: Notice) {
    const overview = notice.qualificationSummary;
    const samrye = samryeQualificationSummary(notice);
    const tables = qualificationTables(overview);
    const household = gyeongjuSummaryCondition(notice, 'household');
    const income = overview
        ? reviewedSummaryDetail(notice, 'income')
        : gyeongjuSummaryCondition(notice, 'income');
    const selection = gyeongjuSummaryCondition(notice, 'selection');
    const scoreEvidence = tables
        ? reviewedSummaryDetail(notice, 'score')?.tableEvidence
        : undefined;
    const rows = rawQualificationSummaryRows(notice, tables, samrye).map(
        (row) => {
            let { label, text } = row;
            // Select reviewed fallbacks from source values, before display normalization.
            if (!overview?.reviewRequired && !overview?.detail) {
                if (
                    household &&
                    income &&
                    text === '신청 자격은 조건 탭에서 항목별로 확인해 주세요.'
                )
                    text =
                        '무주택세대구성원으로, 월평균소득 150% 이하와 자산 기준을 충족해야 해요.';
                if (
                    selection &&
                    text ===
                        '선정 기준은 조건 탭과 공식 공고에서 확인해 주세요.'
                ) {
                    label = '선정 방식';
                    text =
                        '주택형별로 추첨하고 순번대로 동·호를 골라요. 계약 후 자격을 확인해요.';
                }
            }
            return {
                label,
                text: conciseNoticeText(noticeSummaryText(text, notice)),
            };
        },
    );
    const reviewedDetails = overview?.reviewRequired
        ? []
        : [...(samrye?.details ?? []), household, income, selection].filter(
              (detail): detail is StructuredConditionDetail => !!detail,
          );
    return {
        rows,
        tables,
        scoreEvidence,
        detail: overview?.detail
            ? noticeSummaryText(overview.detail, notice)
            : null,
        evidence: scoreEvidence ? null : overview?.evidence,
        reviewedDetails,
    };
}

function rawQualificationSummaryRows(
    notice: Notice,
    tables: ReturnType<typeof qualificationTables>,
    samrye: ReturnType<typeof samryeQualificationSummary>,
) {
    if (samrye) return samrye.rows;
    const overview = notice.qualificationSummary;
    const audience = supportSummaryRows(notice.summary).find(
        (row) => row.label === '지원 대상',
    )?.text;
    if (tables && overview) {
        const fact = (label: string) =>
            tables.facts.find(([name]) => name === label)?.[1];
        const documentStage = fact('서류 대상 선정');
        // Keep an unfamiliar selection rule verbatim rather than guessing the first tiebreaker.
        const scoreFirst = overview.selection.match(
            /^배점이 높은 순 → ([^→]+ 순) → /u,
        );
        return [
            {
                label: '신청 자격',
                text: audience ?? fact('신청 자격') ?? overview.eligibility,
            },
            ...(documentStage
                ? [
                      {
                          label: '서류 대상 선정',
                          text: documentStage.replace(
                              /^경쟁 시 추첨해요\. 서류 제출 후 자격을 충족한 신청자끼리 최종 순위를 정해요\.$/u,
                              '경쟁 시 추첨',
                          ),
                      },
                  ]
                : []),
            {
                label: '최종 선정',
                text: scoreFirst
                    ? `배점순 · 동점이면 ${scoreFirst[1]}`
                    : overview.selection,
            },
        ];
    }
    return [
        {
            label: '신청 자격',
            text:
                (overview?.eligibility &&
                !/^신청 자격은 조건 탭에서 항목별로 확인해 주세요\.$/u.test(
                    overview.eligibility,
                )
                    ? overview.eligibility
                    : audience) ??
                '신청 자격은 조건 탭에서 항목별로 확인해 주세요.',
        },
        {
            label: '선정 순위',
            text:
                overview?.selection ??
                '선정 기준은 조건 탭과 공식 공고에서 확인해 주세요.',
        },
    ];
}
