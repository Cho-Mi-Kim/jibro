import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const packagePath = resolve(
    root,
    "data/notices/pohang-bluevalley-20261121.json",
);
const pdf = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69143584";
const forms = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69143435";
const reviewedPdfHash =
    "f5a88f267a5931d8423e14d62f35ee6cff02ac19e8bbf2404e05c48542567a19";
const reviewedFormsHash =
    "0ad96115e3edd84438cbe046025c80a98b728a74bd54fec57a2512408b9506d2";
const categoryDocuments = {
    finance: ["INTEGRATED"],
    assets: ["INTEGRATED"],
    youth: ["YOUTH", "EARLY_CAREER"],
    marriage: ["NEWLYWED"],
    premarital: ["PREMARITAL"],
    work: ["INDUSTRIAL"],
    subscription: [
        "YOUTH",
        "EARLY_CAREER",
        "NEWLYWED",
        "PREMARITAL",
        "SINGLE_PARENT",
        "INDUSTRIAL",
    ],
};

function officialUrl(value) {
    const url = new URL(value);
    assert.equal(url.origin, "https://apply.lh.or.kr");
    assert.ok(!url.username && !url.password);
    return url;
}

/** Pure editorial-package validation. No service call or database write. */
export function validatePohangReview(review, baseline) {
    assert.equal(review.noticeId, "c4d5dcea-265f-48cb-8fff-c11f9bded820");
    assert.equal(review.packageId, "pohang-bluevalley-editorial-20261121");
    assert.equal(review.disposition, "PASS");
    assert.equal(review.content.questions.length, 15);
    assert.equal(review.content.documents.length, 16);
    const { content } = review;
    const questions = new Map(content.questions.map((q) => [q.id, q]));
    const documents = new Map(content.documents.map((d) => [d.id, d]));
    assert.equal(questions.size, content.questions.length);
    assert.equal(documents.size, content.documents.length);
    for (const list of [
        content.questions,
        content.documents,
        content.schedules,
    ]) {
        assert.equal(new Set(list.map((item) => item.id)).size, list.length);
        for (const item of list) assert.match(item.id, /^[A-Za-z0-9_-]{1,64}$/);
    }
    assert.equal(
        officialUrl(content.evidence.url).searchParams.get("panId"),
        "2015122300020963",
    );
    const sources = new Map(
        review.sourceReviews.map((source) => [source.url, source]),
    );
    assert.equal(sources.get(pdf)?.sha256, reviewedPdfHash);
    assert.equal(sources.get(forms)?.sha256, reviewedFormsHash);
    for (const source of review.sourceReviews) {
        officialUrl(source.url);
        assert.match(source.sha256, /^[a-f0-9]{64}$/);
    }
    for (const item of [...content.questions, ...content.documents]) {
        const source = officialUrl(item.evidence.url);
        assert.equal(source.origin + source.pathname + source.search, pdf);
        const page = new URLSearchParams(source.hash.slice(1)).get("page");
        assert.ok(
            page && Number(page) >= 1 && Number(page) <= 31,
            `Missing exact PDF page: ${item.id}`,
        );
        assert.equal(item.evidence.checkedAt, "2026-11-21");
    }
    for (const question of content.questions) {
        assert.ok(question.title && question.reason);
        assert.equal(question.reviewRequired, false);
        assert.ok(["SINGLE", "MULTIPLE"].includes(question.inputKind));
        assert.equal(
            new Set(question.options.map((option) => option.value)).size,
            question.options.length,
        );
        assert.equal(
            question.options.find((option) => option.value === "UNKNOWN")
                ?.requiresFollowUp,
            true,
        );
    }
    for (const document of content.documents) {
        assert.ok(document.title && document.method && document.issuer);
        if (document.issueUrl)
            assert.equal(officialUrl(document.issueUrl).href, forms);
        if (!document.conditionQuestionId) continue;
        const question = questions.get(document.conditionQuestionId);
        assert.ok(question, `Missing question for ${document.id}`);
        assert.ok(
            document.conditionAnswerValues?.length,
            `Missing answer values for ${document.id}`,
        );
        for (const value of document.conditionAnswerValues) {
            assert.ok(
                question.options.some((option) => option.value === value),
                `Unknown document answer: ${document.id}/${value}`,
            );
            assert.ok(
                !["ACK", "NONE", "UNKNOWN"].includes(value),
                `Acknowledgement must not control document inclusion: ${document.id}`,
            );
        }
    }
    for (const [id, categories] of Object.entries(categoryDocuments)) {
        assert.equal(
            documents.get(id)?.conditionQuestionId,
            "category",
            `${id} must follow application category`,
        );
        assert.deepEqual(documents.get(id)?.conditionAnswerValues, categories);
    }
    assert.equal(
        questions
            .get("category")
            ?.options.find((option) => option.value === "NONE")
            ?.requiresFollowUp,
        true,
    );
    assert.equal(
        questions
            .get("work")
            ?.options.find((option) => option.value === "NOT_MY_TYPE")
            ?.requiresFollowUp,
        false,
    );
    assert.equal(
        questions
            .get("work")
            ?.options.find((option) => option.value === "DOES_NOT_MEET")
            ?.requiresFollowUp,
        true,
    );
    for (const id of [
        "selection",
        "renewal",
        "applies-finance",
        "applies-assets",
        "applies-youth",
        "applies-marriage",
        "applies-work",
        "applies-subscription",
    ]) {
        assert.deepEqual(
            questions.get(id)?.options.map((option) => option.value),
            ["ACK", "UNKNOWN"],
        );
    }
    assert.equal(
        documents.get("family")?.conditionQuestionId,
        "applies-family",
    );
    assert.equal(
        content.documents.filter((document) => document.id === "family").length,
        1,
    );
    assert.match(
        questions.get("applies-family")?.reason ?? "",
        /첫 번째 선택지/,
    );
    assert.equal(questions.get("applies-pregnancy")?.inputKind, "MULTIPLE");
    assert.equal(
        questions.get("applies-pregnancy")?.constraints?.maxSelections,
        2,
    );
    assert.deepEqual(documents.get("pregnancy")?.conditionAnswerValues, [
        "PREGNANT",
    ]);
    assert.equal(
        documents.get("adoption")?.conditionQuestionId,
        "applies-pregnancy",
    );
    assert.deepEqual(documents.get("adoption")?.conditionAnswerValues, [
        "ADOPTED",
    ]);
    assert.match(
        documents.get("adoption")?.method ?? "",
        /입주지정기간 시작일 이후/,
    );
    assert.match(documents.get("adoption")?.evidence.url ?? "", /#page=28$/);
    assert.match(documents.get("marriage")?.evidence.url ?? "", /#page=17$/);
    assert.ok(
        !content.documents.some((document) =>
            /예비신혼.*초본/.test(document.title),
        ),
    );
    if (baseline) {
        assert.deepEqual(
            content.schedules,
            baseline.content.schedules,
            "Schedules must remain unchanged",
        );
        assert.deepEqual(
            content.attachments,
            baseline.content.attachments,
            "Attachments must remain unchanged",
        );
        assert.equal(review.startDate, baseline.start_date);
        assert.equal(review.endDate, baseline.end_date);
    }
    return {
        questions: questions.size,
        documents: documents.size,
        checkedSources: sources.size,
    };
}

if (
    process.argv[1] &&
    resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
    const review = JSON.parse(readFileSync(packagePath, "utf8"));
    const baselineFlag = process.argv.find((value) =>
        value.startsWith("--baseline="),
    );
    const baseline = baselineFlag
        ? JSON.parse(
              readFileSync(
                  resolve(baselineFlag.slice("--baseline=".length)),
                  "utf8",
              ).replace(/^\uFEFF/, ""),
          ).notice
        : undefined;
    console.log(
        JSON.stringify({
            ...validatePohangReview(review, baseline),
            databaseChanges: false,
        }),
    );
}
