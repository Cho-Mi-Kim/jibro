import contracts from './reviewedConditions/specialized-contracts.json';
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { conditionHeading, conditionOverview } from './conditionOverview';
import { pohangConditionDetail } from './pohangConditionDetail';
import { pohangReviewedQuestions } from './pohangReviewedQuestions';
import { pohangSource } from './pohangConditionSource';
import type { Question } from './types';

function question(id: string): Question {
    const entry = pohangReviewedQuestions[id];
    const original = contracts.find(
        (value) =>
            value.questionId === id &&
            [entry.reason, entry.revisedReason].includes(value.sourceReason) &&
            value.sourceEvidence.url.startsWith(pohangSource.url),
    )!;
    return {
        ...(original.answerContract as Pick<
            Question,
            | 'inputKind'
            | 'options'
            | 'constraints'
            | 'applicability'
            | 'answerDependencies'
        >),
        id,
        title: original.sourceTitle,
        reason: original.sourceReason,
        evidence: {
            label: `공고문 ${entry.sourcePage}쪽`,
            url: `${pohangSource.url}#page=${entry.sourcePage}`,
            checkedAt: '2026-11-21',
        },
    };
}

describe('포항블루밸리의 원문 대조 조건 설명', () => {
    it('새 안내의 15개 질문에도 같은 제목·짧은 요약·검수 설명을 적용한다', () => {
        const review = JSON.parse(
            readFileSync(
                '../data/notices/pohang-bluevalley-20261121.json',
                'utf8',
            ),
        );
        const questions = review.content.questions as Question[];
        expect(questions).toHaveLength(15);
        for (const q of questions) {
            expect(conditionHeading(q)).toBe(q.title);
            expect(pohangConditionDetail(q)).toBeDefined();
            expect(conditionOverview(q, []).preview.length).toBeLessThanOrEqual(
                52,
            );
        }
    });
    it.each(Object.keys(pohangReviewedQuestions))(
        '%s는 짧은 기준과 추가 설명 및 해당 쪽 근거를 따로 제공한다',
        (id) => {
            const q = question(id);
            const result = conditionOverview(q, []);
            expect(result.preview.length).toBeLessThanOrEqual(52);
            expect(result.preview).not.toContain('?');
            expect(result.detail.length).toBeGreaterThan(result.preview.length);
            expect(result.structured).toBeDefined();
            expect(conditionHeading(q)).toBe(
                pohangReviewedQuestions[id].heading,
            );
            const evidence = result.structured!.sections.flatMap(
                (section) => section.evidence,
            );
            expect(evidence.length).toBeGreaterThan(0);
            for (const source of evidence) {
                expect(source.label).toMatch(/^공고문 \d+쪽 보기$/);
                expect(source.url).toContain(pohangSource.sha256);
            }
        },
    );

    it('청년의 나이 경로와 사회초년생의 근로 기간 경로를 구분한다', () => {
        const detail = pohangConditionDetail(question('category'))!.text;
        expect(detail).toMatch(/19.*39/);
        expect(detail).toContain('7년');
        expect(detail).toContain('사회초년생');
        expect(detail).not.toContain('청년 근로기간 7년');
    });

    it('산단근로자는 현재 근무와 인접 지역 및 근무 기간을 알 수 있다', () => {
        const detail = pohangConditionDetail(question('work'))!.text;
        for (const name of ['경주', '영천', '청송', '영덕'])
            expect(detail).toContain(name);
        expect(detail).toContain('현재');
        expect(detail).toContain('1년');
    });

    it('서류 확인을 다시 묻는 대신 실제 서류와 서명·명의·위임 방식을 설명한다', () => {
        expect(
            pohangConditionDetail(question('applies-finance'))!.text,
        ).toContain('만 14세 미만');
        expect(
            pohangConditionDetail(question('applies-youth'))!.text,
        ).toContain('전체 이력');
        expect(
            pohangConditionDetail(question('applies-subscription'))!.text,
        ).toContain('배우자');
        const proxy = pohangConditionDetail(question('applies-proxy'))!.text;
        expect(proxy).toContain('인감 방식');
        expect(proxy).toContain('본인서명 방식');
    });

    it('원문이나 문구·쪽수·지문이 바뀌거나 확인 필요면 기존 검토 설명을 재사용하지 않는다', () => {
        const q = question('category');
        for (const changed of [
            { ...q, reason: '' },
            { ...q, reason: '다른 공고의 기준' },
            { ...q, reviewRequired: true },
            {
                ...q,
                evidence: { ...q.evidence, url: pohangSource.url + '#page=99' },
            },
            {
                ...q,
                evidence: {
                    ...q.evidence,
                    url: q.evidence.url + '&sha256=' + '0'.repeat(64),
                },
            },
        ]) {
            expect(pohangConditionDetail(changed)).toBeUndefined();
            expect(conditionHeading(changed)).toBe(q.title);
        }
        expect(
            conditionOverview(q, [
                {
                    id: 'source-conflict',
                    area: 'CONDITION',
                    title: '공식 기준 확인',
                    detail: '원문 변경',
                    questionIds: ['category'],
                    documentIds: [],
                    evidence: [],
                },
            ]).structured,
        ).toBeUndefined();
    });
});
