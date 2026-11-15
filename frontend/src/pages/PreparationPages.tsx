import { useOutletContext } from 'react-router';
import { ExperienceNotes } from './ArchivePage';
import { State } from '../shared/ui/ui';
import type { Preparation } from '../features/housing/types';
import { PreparationEmptyState } from '../features/preparation/PreparationEmptyState';
import { PreparationOverview } from '../features/preparation/PreparationOverview';
import { usePreparation } from '../features/preparation/usePreparation';

export function PreparationListPage() {
    const active = useOutletContext<Preparation | undefined>();
    return (
        <>
            {!active && <PreparationEmptyState />}
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
