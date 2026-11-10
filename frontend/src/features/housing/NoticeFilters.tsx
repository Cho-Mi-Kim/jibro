import { useEffect, useRef, useState } from 'react';
import { Icon, type IconName } from '../../shared/ui/Icon';
import { Button } from '../../shared/ui/ui';
import { statusLabels } from './noticeLabels';
import styles from './NoticeFilters.module.css';

type FilterKey = 'region' | 'type' | 'status';
interface Group {
    key: FilterKey;
    label: string;
    icon: IconName;
    options: string[];
}
interface Props {
    regions: string[];
    types: string[];
    params: URLSearchParams;
    onApply: (params: URLSearchParams) => void;
}
function choices(
    group: Group,
    values: string[],
    change: (next: string[]) => void,
) {
    return group.options.map((value) => (
        <label key={value} className={styles.choice}>
            <input
                type="checkbox"
                checked={values.includes(value)}
                onChange={() =>
                    change(
                        values.includes(value)
                            ? values.filter((x) => x !== value)
                            : [...values, value],
                    )
                }
            />
            {group.key === 'status'
                ? statusLabels[value as keyof typeof statusLabels]
                : value}
        </label>
    ));
}
function FilterSheet({
    group,
    selected,
    apply,
    close,
}: {
    group: Group;
    selected: string[];
    apply: (values: string[]) => void;
    close: () => void;
}) {
    const [draft, setDraft] = useState(selected);
    const dialog = useRef<HTMLDialogElement>(null);
    useEffect(() => {
        const element = dialog.current!;
        element.showModal();
        const layout = window.matchMedia?.('(min-width: 1024px)');
        const onResize = () => {
            if (layout?.matches) element.close();
        };
        layout?.addEventListener('change', onResize);
        return () => {
            layout?.removeEventListener('change', onResize);
        };
    }, []);
    return (
        <dialog
            ref={dialog}
            className={styles.sheet}
            aria-label={group.label + ' 필터'}
            onClose={close}
        >
            <header>
                <h2>{group.label}</h2>
                <button
                    type="button"
                    aria-label="닫기"
                    className={styles.close}
                    onClick={() => dialog.current?.close()}
                >
                    <span aria-hidden="true">×</span>
                </button>
            </header>
            <fieldset>
                <legend>여러 항목을 선택할 수 있어요</legend>
                {choices(group, draft, setDraft)}
                {group.options.length === 0 && <p>선택할 항목이 없습니다.</p>}
            </fieldset>
            <footer>
                <Button className="secondary" onClick={() => setDraft([])}>
                    초기화
                </Button>
                <Button
                    onClick={() => {
                        apply(draft);
                        dialog.current?.close();
                    }}
                >
                    적용
                </Button>
            </footer>
        </dialog>
    );
}
export function NoticeFilters({ regions, types, params, onApply }: Props) {
    const [open, setOpen] = useState<FilterKey | null>(null);
    const groups: Group[] = [
        { key: 'region', label: '지역', icon: 'pin', options: regions },
        { key: 'type', label: '유형', icon: 'file', options: types },
        {
            key: 'status',
            label: '상태',
            icon: 'clock',
            options: Object.keys(statusLabels),
        },
    ];
    const group = groups.find((item) => item.key === open);
    function apply(key: FilterKey, values: string[]) {
        const next = new URLSearchParams(params);
        next.delete(key);
        values.forEach((v) => next.append(key, v));
        next.set('page', '0');
        onApply(next);
    }
    return (
        <aside className={styles.panel} aria-label="공고 필터">
            <h2>검색 조건</h2>
            <div className={styles.triggers}>
                {groups.map((item) => (
                    <button
                        key={item.key}
                        type="button"
                        aria-haspopup="dialog"
                        onClick={() => setOpen(item.key)}
                    >
                        <Icon name={item.icon} />
                        <span>
                            {item.label}
                            {params.has(item.key)
                                ? ' · ' + params.getAll(item.key).length
                                : ''}
                        </span>
                        <Icon name="down" />
                    </button>
                ))}
            </div>
            <div className={styles.desktop}>
                {groups.map((item) => (
                    <fieldset key={item.key}>
                        <legend>{item.label}</legend>
                        {choices(item, params.getAll(item.key), (values) =>
                            apply(item.key, values),
                        )}
                    </fieldset>
                ))}
            </div>
            {groups.some((item) => params.has(item.key)) && (
                <div className={styles.applied}>
                    {groups.flatMap((item) =>
                        params.getAll(item.key).map((value) => (
                            <button
                                key={item.key + ':' + value}
                                aria-label={
                                    (statusLabels[
                                        value as keyof typeof statusLabels
                                    ] ?? value) + ' 필터 해제'
                                }
                                onClick={() =>
                                    apply(
                                        item.key,
                                        params
                                            .getAll(item.key)
                                            .filter((v) => v !== value),
                                    )
                                }
                            >
                                {statusLabels[
                                    value as keyof typeof statusLabels
                                ] ?? value}{' '}
                                ×
                            </button>
                        )),
                    )}
                    <button
                        onClick={() => {
                            const next = new URLSearchParams(params);
                            groups.forEach((item) => next.delete(item.key));
                            next.set('page', '0');
                            onApply(next);
                        }}
                    >
                        필터 초기화
                    </button>
                </div>
            )}
            {group && (
                <FilterSheet
                    group={group}
                    selected={params.getAll(group.key)}
                    apply={(values) => apply(group.key, values)}
                    close={() => setOpen(null)}
                />
            )}
        </aside>
    );
}
