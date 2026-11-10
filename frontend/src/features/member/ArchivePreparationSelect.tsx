import { useEffect, useId, useRef, useState } from 'react';
import { Icon } from '../../shared/ui/Icon';
import styles from './ArchivePreparationSelect.module.css';

export function ArchivePreparationSelect({
    items,
    value,
    onChange,
}: {
    items: { id: string; title: string }[];
    value: string;
    onChange: (id: string) => void;
}) {
    const id = useId();
    const root = useRef<HTMLDivElement>(null);
    const trigger = useRef<HTMLButtonElement>(null);
    const options = useRef<(HTMLButtonElement | null)[]>([]);
    const [open, setOpen] = useState(false);
    const [focused, setFocused] = useState(0);
    const search = useRef({ text: '', time: 0 });
    const selectedIndex = Math.max(
        0,
        items.findIndex((item) => item.id === value),
    );

    useEffect(() => {
        if (!open) return;
        options.current[focused]?.focus({ preventScroll: true });
        options.current[focused]?.scrollIntoView({ block: 'nearest' });
    }, [open, focused]);

    useEffect(() => {
        if (!open) return;
        function outside(event: PointerEvent) {
            if (!root.current?.contains(event.target as Node)) setOpen(false);
        }
        document.addEventListener('pointerdown', outside);
        return () => document.removeEventListener('pointerdown', outside);
    }, [open]);

    function show(index = selectedIndex) {
        setFocused(index);
        setOpen(true);
        search.current = { text: '', time: 0 };
    }
    function close() {
        setOpen(false);
        trigger.current?.focus();
    }

    return (
        <div
            ref={root}
            className={styles.root}
            onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget))
                    setOpen(false);
            }}
        >
            <span id={`${id}-label`} className={styles.label}>
                현재 준비 공고
            </span>
            <button
                ref={trigger}
                type="button"
                role="combobox"
                aria-labelledby={`${id}-label`}
                aria-expanded={open}
                aria-controls={`${id}-list`}
                aria-haspopup="listbox"
                className={styles.trigger}
                onClick={() => (open ? close() : show())}
                onKeyDown={(event) => {
                    if (
                        ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(
                            event.key,
                        )
                    ) {
                        event.preventDefault();
                        show(
                            event.key === 'Home'
                                ? 0
                                : event.key === 'End'
                                  ? items.length - 1
                                  : selectedIndex,
                        );
                    }
                }}
            >
                <span>{items[selectedIndex]?.title}</span>
                <Icon name="down" />
            </button>
            {open && (
                <div
                    id={`${id}-list`}
                    role="listbox"
                    aria-labelledby={`${id}-label`}
                    className={styles.list}
                    onKeyDown={(event) => {
                        const last = items.length - 1;
                        if (event.key === 'Escape') {
                            event.preventDefault();
                            event.stopPropagation();
                            close();
                        } else if (
                            ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(
                                event.key,
                            )
                        ) {
                            event.preventDefault();
                            setFocused(
                                event.key === 'Home'
                                    ? 0
                                    : event.key === 'End'
                                      ? last
                                      : event.key === 'ArrowDown'
                                        ? Math.min(last, focused + 1)
                                        : Math.max(0, focused - 1),
                            );
                        } else if (
                            event.key.length === 1 &&
                            event.key !== ' ' &&
                            !event.ctrlKey &&
                            !event.metaKey &&
                            !event.altKey
                        ) {
                            const now = Date.now();
                            search.current = {
                                text:
                                    (now - search.current.time < 700
                                        ? search.current.text
                                        : '') + event.key.toLocaleLowerCase(),
                                time: now,
                            };
                            const index = items.findIndex((item) =>
                                item.title
                                    .toLocaleLowerCase()
                                    .startsWith(search.current.text),
                            );
                            if (index >= 0) setFocused(index);
                        }
                    }}
                >
                    {items.map((item, index) => (
                        <button
                            key={item.id}
                            ref={(element) => {
                                options.current[index] = element;
                            }}
                            type="button"
                            role="option"
                            aria-selected={item.id === value}
                            tabIndex={index === focused ? 0 : -1}
                            className={styles.option}
                            onFocus={() => setFocused(index)}
                            onClick={() => {
                                onChange(item.id);
                                close();
                            }}
                        >
                            <span>{item.title}</span>
                            <span className={styles.check} aria-hidden="true">
                                {item.id === value && <Icon name="check" />}
                            </span>
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}
