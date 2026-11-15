import type { Notice, Schedule } from './types';
import {
    applicationDate,
    dateWithScheduleTime,
    scheduleDay,
    scheduleKind,
    scheduleTime,
    selectSchedule,
} from './schedules';

const titleDate = String.raw`(?:\d{4}|\d{2})\s*(?:[./-]|년)\s*\d{1,2}\s*(?:[./-]|월)\s*\d{1,2}(?:일|\.)?`;
const titleDateLabel = String.raw`(?:공고(?:일)?\s*[:：]?\s*)?${titleDate}\s*(?:공고)?`;
const trailingTitleDate = new RegExp(
    String.raw`(?:\s*[([]\s*${titleDateLabel}\s*[)\]]|\s+${titleDateLabel})\s*$`,
);

/** 목록 표시만 줄인다. 검색·상세·접근성 이름에는 원문 제목을 사용한다. */
export function noticeListTitle(title: string) {
    const shortened = title
        .replace(trailingTitleDate, '')
        .replace(
            /^\s*(?:\[\s*정정\s*공고\s*\]|\(\s*정정\s*공고\s*\)|정정\s*공고(?=\s|$))/,
            '[정정]',
        )
        .replace(/^(\s*(?:\[[^\]]+\]\s*)*)20\d{2}(?:\s*년도?)?\s+(?=\S)/, '$1')
        // 논현2 공식 첨부 공고문(fileid=69314336)으로 확인한 목록 원문 오타.
        .replace(/(인천\s*논현\s*2\s*단지\s*)국임임대주택/g, '$1국민임대주택')
        .replace(/(국민|공공|매입|영구|전세)\s*임대\s*주택/g, '$1임대')
        .replace(/입주\s*자격\s*완화/g, '자격완화')
        .replace(
            /(예비\s*)?입주자\s*추가\s*모집(?:\s*공고)?(?=\s*(?:[([][^)\]]*[)\]]\s*)*$)/g,
            (_match, reserve: string | undefined) =>
                reserve ? '예비입주자 추가모집' : '추가모집',
        )
        .replace(
            /자격\s*완화\s*모집\s*공고(?=\s*(?:[([][^)\]]*[)\]]\s*)*$)/g,
            '자격완화',
        )
        .replace(
            /(?:(예비\s*입주자|입주자)\s*(?:(?:수시|상시)\s*)?모집|(?:수시|상시)\s*모집)(?:\s*공고)?(?=\s*(?:[([][^)\]]*[)\]]\s*)*$)/g,
            (_match, kind: string | undefined) =>
                kind?.replace(/\s/g, '') === '예비입주자' ? '예비입주자' : '',
        )
        .replace(/\s+/g, ' ')
        .trim();
    return shortened || title;
}

export function noticeDaysLeft(
    notice: Pick<
        Notice,
        'status' | 'startDate' | 'endDate' | 'applicationTiming' | 'schedules'
    >,
    now = new Date(),
) {
    return notice.status === 'CLOSED'
        ? '일정 경과'
        : daysLeft(applicationDate(notice, 'end'), now);
}
export function displayDate(value?: string | null) {
    if (!value) return '확인 필요';
    const day = scheduleDay(value);
    if (day) {
        const time = scheduleTime({ date: value, title: '' });
        return day.replaceAll('-', '.') + (time ? ' ' + time : '');
    }
    return value.replace(/-/g, '.').replace('T', ' ').slice(0, 16);
}
export function homeScheduleSummary(event: { title: string; date: string }) {
    const time = scheduleTime(event);
    const title = event.title
        .replace(/\([^)]*(?:\d{1,2}:\d{2}|조기\s*중단)[^)]*\)/g, '')
        .replace(/조기\s*중단\s*가능/g, '')
        .trim();
    const sourceDay = scheduleDay(event.date);
    const day = sourceDay
        ? sourceDay.slice(5, 10).replace('-', '.')
        : '확인 필요';
    return { title, date: day + (time ? ' ' + time : '') };
}
export function preparationDeadlineLabel(
    endDate: string | null | undefined,
    schedules: Pick<Schedule, 'title' | 'date'>[],
) {
    if (!endDate) return '확인 필요';
    const parts = scheduleDay(endDate)?.match(/^\d{4}-(\d{2})-(\d{2})/);
    if (!parts) return '확인 필요';
    const closing = selectSchedule(schedules, 'end', endDate);
    const time =
        scheduleTime({
            date: endDate,
            title: closing?.title ?? '',
        }) || (closing ? scheduleTime(closing) : '');
    return `${Number(parts[1])}월 ${Number(parts[2])}일${time ? ' ' + time : ''}`;
}
export function daysLeft(value?: string | null, now = new Date()) {
    if (!value) return '일정 확인';
    const day = scheduleDay(value);
    if (!day) return '일정 확인';
    if (value.length > 10) {
        const instant = Date.parse(
            /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value + '+09:00',
        );
        if (Number.isFinite(instant) && now.getTime() >= instant)
            return '일정 경과';
    }
    const today = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).format(now);
    const left = Math.round(
        (Date.parse(day + 'T00:00:00+09:00') -
            Date.parse(today + 'T00:00:00+09:00')) /
            86400000,
    );
    if (!Number.isFinite(left)) return '일정 확인';
    if (left < 0) return '일정 경과';
    return left === 0 ? 'D-Day' : 'D-' + left;
}
export function nextSchedule(
    schedules: Pick<Schedule, 'title' | 'date'>[],
    endDate?: string | null,
    now = new Date(),
) {
    const events =
        endDate && !selectSchedule(schedules, 'end', endDate)
            ? [...schedules, { title: '신청 접수 마감', date: endDate }]
            : schedules;
    return events
        .filter((event) =>
            /^D-(?:Day|\d+)$/.test(
                daysLeft(dateWithScheduleTime(event.date, event), now),
            ),
        )
        .sort((first, second) => first.date.localeCompare(second.date))[0];
}

export function nextNoticeSchedule(
    notice: Notice | undefined,
    schedules: Pick<Schedule, 'title' | 'date'>[],
    now = new Date(),
) {
    const events =
        notice?.status === 'CLOSED'
            ? schedules.filter(
                  (event) =>
                      !['start', 'end', 'application'].includes(
                          scheduleKind(event.title),
                      ),
              )
            : schedules;
    return nextSchedule(
        events,
        notice?.status === 'CLOSED' ||
            notice?.lifecycle?.state === 'EARLY_CLOSED' ||
            notice?.lifecycle?.state === 'CANCELLED'
            ? undefined
            : notice?.endDate,
        now,
    );
}
