import { Link, useOutletContext } from 'react-router';
import { ExperienceNotes } from './ArchivePage';
import { cx } from '../shared/styles/classes';
import { Card, State } from '../shared/ui/ui';
import type { Preparation } from '../features/housing/types';
import { PreparationOverview } from '../features/preparation/PreparationOverview';
import { usePreparation } from '../features/preparation/usePreparation';

export function PreparationListPage() {
    const active = useOutletContext<Preparation | undefined>();
    return (
        <>
            {!active && (
                <Card>
                    <h2>첫 신청 준비를 시작해 보세요</h2>
                    <Link className={cx('link-button')} to="/notices">
                        공고 둘러보기
                    </Link>
                </Card>
            )}
            {active && (
                <PreparationOverview
                    p={active}
                    notes={<ExperienceNotes id={active.id} />}
                />
            )}
        </>
    );
}

export function PreparationPage() {
    const query = usePreparation();
    return (
        <>
            <State
                loading={query.isPending}
                error={query.error}
                retry={() => void query.refetch()}
            />
            {query.data && (
                <PreparationOverview
                    p={query.data}
                    notes={<ExperienceNotes id={query.data.id} />}
                />
            )}
        </>
    );
}
