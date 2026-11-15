import type { Notice } from './types';
import { applicationInterrupted, lifecycleLabel } from './noticeLifecycle';
import { EvidenceLink } from './EvidenceLink';
import { Icon } from '../../shared/ui/Icon';
import styles from './NoticeLifecycleNotice.module.css';

export function NoticeLifecycleNotice({
    notice,
    preparation = false,
}: {
    notice?: Pick<Notice, 'lifecycle' | 'status'>;
    preparation?: boolean;
}) {
    if (!notice || !applicationInterrupted(notice)) return null;
    const lifecycle = notice.lifecycle!;
    return (
        <aside className={styles.notice} aria-label="공식 상태 안내">
            <strong>
                <Icon name="info" />
                {lifecycleLabel(notice)}
            </strong>
            <p>{lifecycle.reason}</p>
            <p>
                {preparation
                    ? '기존 답변과 서류 준비 기록은 보존됩니다. 아래 날짜는 기존 공고 일정입니다.'
                    : '새 조건 확인을 시작할 수 없습니다. 아래 날짜는 기존 공고 일정입니다.'}
            </p>
            {lifecycle.evidence && (
                <EvidenceLink evidence={lifecycle.evidence} />
            )}
        </aside>
    );
}
