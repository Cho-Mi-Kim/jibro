import { useEffect, useRef } from 'react';
import { Icon } from '../../shared/ui/Icon';
import styles from './NoticeSort.module.css';

const options = [
    {
        value: 'deadline',
        label: '마감 가까운 순',
        description: '신청 마감이 가까운 공고부터',
    },
    { value: 'newest', label: '최신순', description: '최근 게시된 공고부터' },
];

export function NoticeSort({
    value,
    onChange,
}: {
    value: string;
    onChange: (value: string) => void;
}) {
    const root = useRef<HTMLDetailsElement>(null);
    useEffect(() => {
        function outside(event: PointerEvent) {
            if (root.current && !root.current.contains(event.target as Node))
                root.current.open = false;
        }
        function escape(event: KeyboardEvent) {
            if (event.key === 'Escape' && root.current?.open) {
                root.current.open = false;
                root.current.querySelector('summary')?.focus();
            }
        }
        document.addEventListener('pointerdown', outside);
        document.addEventListener('keydown', escape);
        return () => {
            document.removeEventListener('pointerdown', outside);
            document.removeEventListener('keydown', escape);
        };
    }, []);
    return (
        <details className={styles.root} ref={root}>
            <summary aria-label="공고 정렬">
                정렬 <Icon name="down" />
            </summary>
            <div
                className={styles.menu}
                role="group"
                aria-label="공고 정렬 방식"
            >
                {options.map((option) => (
                    <button
                        key={option.value}
                        type="button"
                        aria-pressed={value === option.value}
                        onClick={() => {
                            onChange(option.value);
                            if (root.current) {
                                root.current.open = false;
                                root.current.querySelector('summary')?.focus();
                            }
                        }}
                    >
                        <span>
                            <strong>{option.label}</strong>
                            <small>{option.description}</small>
                        </span>
                        {value === option.value && <Icon name="check" />}
                    </button>
                ))}
            </div>
        </details>
    );
}
