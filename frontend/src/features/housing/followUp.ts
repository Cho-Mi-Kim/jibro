import { ApiError } from '../../shared/api/client';
import {
    applicationRecordSchedule,
    scheduleKind,
    snapshotPreparationScheduleSource,
} from './schedules';
import type { FollowUpRecord, Preparation, Schedule } from './types';

export const emptyFollowUp: FollowUpRecord = {
    result: 'WAITING',
    waitlistNumber: null,
    documentsSubmitted: false,
    contractCompleted: false,
    movedIn: false,
};

export const followUpResults: Record<FollowUpRecord['result'], string> = {
    WAITING: '결과 대기',
    SELECTED: '선정',
    WAITLIST: '예비 입주',
    NOT_SELECTED: '미선정',
};

export function followUpState(record?: FollowUpRecord | null) {
    const r = record ?? emptyFollowUp;
    if (r.result === 'NOT_SELECTED')
        return {
            label: '미선정',
            hint: '기록을 보관하거나 다른 공고를 살펴보세요.',
            done: false,
        };
    if (r.movedIn)
        return {
            label: '입주 완료',
            hint: '신청 후 기록을 마쳤어요. 필요할 때 보관함에 보관할 수 있어요.',
            done: true,
        };
    if (r.contractCompleted)
        return {
            label: '계약 완료',
            hint: '입주 안내와 남은 절차를 확인해 주세요.',
            done: false,
        };
    if (r.result === 'WAITLIST')
        return {
            label: r.waitlistNumber
                ? `예비 대기 · ${r.waitlistNumber}번`
                : '예비 대기',
            hint: '기관의 추가 안내를 기다리고, 선정되면 결과를 바꿔 주세요.',
            done: false,
        };
    if (r.result === 'SELECTED')
        return {
            label: '선정 · 다음 절차 확인',
            hint: '내게 안내된 서류 제출·계약 일정을 확인해 주세요.',
            done: false,
        };
    return {
        label: r.documentsSubmitted
            ? '서류 제출 완료 · 결과 대기'
            : '결과 대기',
        hint: '공식 발표나 개별 연락으로 결과를 확인한 뒤 기록해 주세요.',
        done: false,
    };
}

export function showFollowUp(p?: Preparation) {
    return !!p && applicationRecordSchedule(p.schedules)?.recorded === true;
}

export type FollowUpStep =
    'result' | 'documentsSubmitted' | 'contractCompleted' | 'movedIn';

// Only explicit, personalized official schedule titles establish a dated step.
// A document-screening announcement is not the final tenant-selection result.
export function followUpSchedules(
    p: Preparation,
): Record<FollowUpStep, Schedule[]> {
    const steps: Record<FollowUpStep, Schedule[]> = {
        result: [],
        documentsSubmitted: [],
        contractCompleted: [],
        movedIn: [],
    };
    for (const event of snapshotPreparationScheduleSource(p).schedules) {
        const title = event.title.replace(/\s/g, '');
        if (scheduleKind(event.title) === 'result') steps.result.push(event);
        if (
            /서류/.test(title) &&
            /제출|보완/.test(title) &&
            !/대상자|발표|선정/.test(title)
        )
            steps.documentsSubmitted.push(event);
        if (/계약/.test(title) && !/해약|해지|취소/.test(title))
            steps.contractCompleted.push(event);
        if (
            /입주(?:기간|예정|지정|시작|일정|안내|개시|일(?=$|[^가-힣]))|^입주$|이사/.test(
                title,
            )
        )
            steps.movedIn.push(event);
    }
    return steps;
}

export function validatedFollowUp(value: unknown): FollowUpRecord {
    const r = value as FollowUpRecord | null;
    if (
        !r ||
        !Object.hasOwn(followUpResults, r.result) ||
        ['documentsSubmitted', 'contractCompleted', 'movedIn'].some(
            (k) =>
                typeof (r as unknown as Record<string, unknown>)[k] !==
                'boolean',
        ) ||
        (r.waitlistNumber !== null &&
            (r.result !== 'WAITLIST' ||
                !Number.isInteger(r.waitlistNumber) ||
                r.waitlistNumber < 1 ||
                r.waitlistNumber > 999999))
    ) {
        throw new ApiError(400, '결과와 예비 순번을 다시 확인해 주세요.');
    }
    return {
        result: r.result,
        waitlistNumber: r.waitlistNumber,
        documentsSubmitted: r.documentsSubmitted,
        contractCompleted: r.contractCompleted,
        movedIn: r.movedIn,
    };
}
