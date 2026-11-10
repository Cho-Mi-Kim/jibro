import { Link, useLocation, useNavigate } from 'react-router';
import { OfficialLink } from '../../shared/ui/HousingUI';
import { Icon } from '../../shared/ui/Icon';
import { Button } from '../../shared/ui/ui';
import { safeUrl } from '../../shared/ui/helpers';
import { startPath } from '../preparation/startIntent';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';

export function NoticeDetailActions({
    notice,
    preparationId,
}: {
    notice: Notice;
    preparationId?: string;
}) {
    const navigate = useNavigate();
    const { state } = useLocation();
    return (
        <footer className={styles.actions}>
            <p className={styles.actionInfo}>
                <Icon name="info" /> 최종 자격과 일정은 공식 공고에서 확인해
                주세요.
            </p>
            <div className={styles.actionButtons}>
                <OfficialLink url={notice.evidence.url} showIcon={false}>
                    <Icon name="external" /> 공고 원문 보기
                </OfficialLink>
                {notice.canStart && (
                    <Button
                        onClick={() =>
                            navigate(
                                preparationId
                                    ? '/preparations/' + preparationId
                                    : startPath(notice.id, notice.version),
                                { state },
                            )
                        }
                    >
                        {preparationId ? '준비 이어하기' : '내 조건 확인하기'}
                        <Icon name="arrow" />
                    </Button>
                )}
            </div>
        </footer>
    );
}

export function NoticeResources({ notice }: { notice: Notice }) {
    return (
        <details className={styles.resources}>
            <summary>
                공고 자료와 비교
                <Icon name="down" />
            </summary>
            <nav aria-label="공고 보조 기능">
                <Link to={'/compare?a=' + notice.id}>공고 비교</Link>
                <Link to={'/notices/' + notice.id + '/versions'}>
                    수정 전후 비교
                </Link>
            </nav>
            {notice.attachments.length > 0 && (
                <section>
                    <h2>공고 첨부 자료</h2>
                    <ul className={styles.attachments}>
                        {notice.attachments.map(
                            ({ label, url, checkedAt }, index) => (
                                <li key={index}>
                                    {url && safeUrl(url) ? (
                                        <a
                                            href={url}
                                            target="_blank"
                                            rel="noreferrer"
                                        >
                                            {label}
                                        </a>
                                    ) : (
                                        label
                                    )}
                                    {' · 확인 '}
                                    {checkedAt}
                                </li>
                            ),
                        )}
                    </ul>
                </section>
            )}
        </details>
    );
}
