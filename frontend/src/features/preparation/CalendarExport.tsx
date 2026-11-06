import { useRef, useState } from 'react';
import { Button } from '../../shared/ui/ui';
import { Icon } from '../../shared/ui/Icon';
import type { Notice, Preparation } from '../housing/types';
import { calendarFile, calendarItems } from './calendar';
import { displayDate } from '../../shared/ui/housingHelpers';
import s from '../member/Controls.module.css';
export function CalendarExport({
    notice,
    preparation,
    inline = false,
    triggerClassName,
    showIcon = false,
}: {
    notice: Notice;
    preparation?: Preparation;
    inline?: boolean;
    triggerClassName?: string;
    showIcon?: boolean;
}) {
    const items = calendarItems(notice, preparation),
        [excluded, setExcluded] = useState<string[]>([]),
        [message, setMessage] = useState('');
    const dialog = useRef<HTMLDialogElement>(null);
    const selected = items.filter((i) => !excluded.includes(i.id));
    function download() {
        const url = URL.createObjectURL(
            new Blob([calendarFile(notice, selected)], {
                type: 'text/calendar;charset=utf-8',
            }),
        );
        const link = document.createElement('a');
        link.href = url;
        link.download = '집으로-' + notice.id + '.ics';
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        setMessage('캘린더 파일을 생성했습니다. 캘린더 앱에서 가져와 주세요.');
    }
    const options = (
        <>
            <p>
                선택한 공식 일정을 복사합니다. 공고 변경 시 다시 가져와야 하며
                개인 답변은 포함되지 않습니다.
            </p>
            {!items.length && (
                <p>공식 근거와 날짜가 함께 확인된 일정이 없습니다.</p>
            )}
            {items.map((i) => (
                <label className={s.choice} key={i.id}>
                    <input
                        type="checkbox"
                        checked={!excluded.includes(i.id)}
                        onChange={(e) =>
                            setExcluded(
                                e.target.checked
                                    ? excluded.filter((x) => x !== i.id)
                                    : [...excluded, i.id],
                            )
                        }
                    />
                    {i.title} · {displayDate(i.date)}
                </label>
            ))}
            <Button disabled={!selected.length} onClick={download}>
                선택 일정 .ics 받기
            </Button>
            {message && <p role="status">{message}</p>}
        </>
    );
    if (inline)
        return (
            <>
                <button
                    type="button"
                    className={triggerClassName ?? s.calendarTrigger}
                    aria-haspopup="dialog"
                    onClick={() => {
                        setMessage('');
                        dialog.current?.showModal();
                    }}
                >
                    {showIcon && <Icon name="calendar" />}
                    캘린더 내보내기
                </button>
                <dialog ref={dialog} aria-label="캘린더 내보내기">
                    <h2>캘린더 내보내기</h2>
                    {options}
                    <Button
                        className="secondary"
                        onClick={() => dialog.current?.close()}
                    >
                        닫기
                    </Button>
                </dialog>
            </>
        );
    return (
        <details className={s.section}>
            <summary>캘린더 파일 내보내기</summary>
            {options}
        </details>
    );
}
