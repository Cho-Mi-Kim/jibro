import { useNotice } from '../../shared/ui/housingHelpers';
import { cx } from '../../shared/styles/classes';
import { Link, NavLink } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { api } from '../housing/api';
import type { Preparation } from '../housing/types';
import { Button, Card } from '../../shared/ui/ui';
import { preparationTabPath, preparationTabs } from './preparationTabRoutes';
import styles from './PreparationHeader.module.css';
import { useSaved } from './usePreparation';
export function GuidanceRefresh({
    p,
    onRefreshed,
}: {
    p: Preparation;
    onRefreshed?: () => void;
}) {
    const notice = useNotice(p.noticeId);
    const saved = useSaved();
    const refresh = useMutation({
        mutationFn: () =>
            api.refreshPreparation(p.id, p.revision, notice.data!.version),
        onSuccess: (next) => {
            saved(next);
            onRefreshed?.();
        },
    });
    if (
        !notice.data ||
        notice.data.guideState !== 'AVAILABLE' ||
        notice.data.version <= p.version
    )
        return null;
    return (
        <Card>
            <h2>새로운 질문과 준비 안내가 있어요</h2>
            <p>
                기존 답변은 이전 기록으로 보관하고, 바뀐 질문은 다시 답변합니다.
                서류 메모는 유지하며 완료한 서류는 재확인이 필요합니다.
            </p>
            <Link to={'/notices/' + p.noticeId + '/versions'}>
                수정 전후 비교
            </Link>
            <Button
                disabled={refresh.isPending}
                onClick={() => refresh.mutate()}
            >
                {refresh.isPending
                    ? '불러오는 중…'
                    : '새 안내로 질문 다시 확인하기'}
            </Button>
            {refresh.error && <p role="alert">{refresh.error.message}</p>}
        </Card>
    );
}
export function PreparationGuidance({ p }: { p: Preparation }) {
    return (
        <>
            <GuidanceRefresh p={p} />
            {p.guideState !== 'AVAILABLE' && (
                <p className={cx('callout')}>
                    현재 기록과 최신 안내가 다르거나 안내 검수 중입니다. 기존
                    기록은 보존되며 새 안내 확인 전에는 저장할 수 없습니다.
                </p>
            )}
        </>
    );
}

export function PreparationNav({
    id,
    overviewIsList,
}: {
    id: string;
    overviewIsList: boolean;
}) {
    return (
        <nav className={styles.tabs} aria-label="개인 준비">
            {preparationTabs.map(([section, label]) => (
                <NavLink
                    key={section}
                    to={
                        !section && overviewIsList
                            ? '/preparations'
                            : preparationTabPath(id, section)
                    }
                    end
                >
                    {label}
                </NavLink>
            ))}
        </nav>
    );
}
