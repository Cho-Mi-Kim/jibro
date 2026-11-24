import { Icon } from './Icon';
import styles from './Pagination.module.css';

export function Pagination({
    page,
    totalPages,
    label,
    onPageChange,
    disabled = false,
}: {
    page: number;
    totalPages: number;
    label: string;
    onPageChange: (page: number) => void;
    disabled?: boolean;
}) {
    if (!Number.isInteger(totalPages) || totalPages <= 0) return null;
    return (
        <nav className={styles.pagination} aria-label={label}>
            <button
                disabled={disabled || page === 0}
                aria-label="이전 페이지"
                onClick={() => onPageChange(page - 1)}
            >
                <Icon name="back" /> 이전
            </button>
            <span
                aria-current="page"
                aria-label={`전체 ${totalPages}페이지 중 ${page + 1}페이지`}
            >
                <strong>{page + 1}</strong> / {totalPages}페이지
            </span>
            <button
                disabled={disabled || page + 1 >= totalPages}
                aria-label="다음 페이지"
                onClick={() => onPageChange(page + 1)}
            >
                다음 <Icon name="arrow" />
            </button>
        </nav>
    );
}
