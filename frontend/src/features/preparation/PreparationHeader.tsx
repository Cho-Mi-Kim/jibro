import { cx } from '../../shared/styles/classes';
import { Link } from 'react-router';
import type { Preparation } from '../housing/types';
import { Disc } from '../../shared/ui/HousingUI';
import { Icon } from '../../shared/ui/Icon';
import styles from './PreparationHeader.module.css';

export function PreparationHeader({
    preparations,
    activeId,
    onSelect,
}: {
    preparations?: Preparation[];
    activeId?: string;
    onSelect?: (id: string) => void;
}) {
    const completed =
        preparations?.filter((p) => p.progress === 100).length ?? 0;
    return (
        <header className={styles.header}>
            <h1 className={cx('visually-hidden')}>내 준비</h1>
            {!!preparations?.length && onSelect ? (
                <details className={styles.selector}>
                    <summary>
                        <Disc name="file" tone="green" />
                        <span className={styles.counts}>
                            <span>
                                진행 중 {preparations.length - completed}건
                            </span>
                            <span>· 준비 기록 완료 {completed}건</span>
                        </span>
                        <Icon name="down" />
                    </summary>
                    <div className={styles.selectionBody}>
                        <label htmlFor="active-preparation">준비 건 선택</label>
                        <select
                            id="active-preparation"
                            value={activeId ?? ''}
                            onChange={(event) => onSelect(event.target.value)}
                        >
                            {preparations.map((p) => (
                                <option key={p.id} value={p.id}>
                                    {p.title}
                                </option>
                            ))}
                        </select>
                        <Link to="/archive">
                            완료 여정 보관함 <Icon name="arrow" />
                        </Link>
                    </div>
                </details>
            ) : (
                <Link className={styles.archive} to="/archive">
                    완료 여정 보관함
                </Link>
            )}
        </header>
    );
}
