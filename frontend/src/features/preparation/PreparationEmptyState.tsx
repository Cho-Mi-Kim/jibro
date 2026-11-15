import { Link } from 'react-router';
import { cx } from '../../shared/styles/classes';
import { Disc } from '../../shared/ui/Disc';
import { Icon } from '../../shared/ui/Icon';
import styles from './PreparationEmptyState.module.css';

export function PreparationEmptyState() {
    return (
        <section
            className={styles.card}
            aria-labelledby="preparation-empty-title"
        >
            <Disc name="file" />
            <h2 id="preparation-empty-title" className={styles.title}>
                준비할 공고를 선택해 주세요
            </h2>
            <p className={styles.description}>
                공고를 고르면 조건·서류·일정을
                <br />
                한곳에서 준비할 수 있어요.
            </p>
            <Link
                className={cx('link-button') + ' ' + styles.browse}
                to="/notices"
            >
                공고 둘러보기
            </Link>
            <div className={styles.secondary}>
                <Link className={styles.archive} to="/archive">
                    <Icon name="bookmark" />
                    완료 여정 보관함
                </Link>
            </div>
        </section>
    );
}
