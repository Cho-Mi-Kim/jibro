import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { getQuestionStep } from './questionNavigation';
import { usePreparation } from './usePreparation';
import { usePreparationRecord } from './usePreparationRecord';
import { State } from '../../shared/ui/ui';
import { allowRecordChange } from '../../shared/ui/useUnsavedChanges';
import { PreparationHeader } from './PreparationHeader';
import { PreparationNav } from './PreparationNav';
import { getPreparationTab, preparationTabPath } from './preparationTabRoutes';
import { useActivePreparation } from './useActivePreparation';
import styles from './PreparationHeader.module.css';

export function PreparationTabLayout() {
    const tab = getPreparationTab(useLocation());
    return tab ? <Workspace tab={tab} /> : <Outlet />;
}

function ConditionContinue() {
    const { data: record } = usePreparation();
    if (
        !record ||
        record.guideState !== 'AVAILABLE' ||
        !record.questions.length
    )
        return null;
    const next = record.questions[getQuestionStep(record)];
    return (
        <footer className={styles.conditionFooter}>
            <Link
                to={
                    '/preparations/' +
                    record.id +
                    '/conditions/edit?' +
                    new URLSearchParams({ question: next.id })
                }
            >
                준비 이어하기
            </Link>
        </footer>
    );
}

function Workspace({
    tab,
}: {
    tab: NonNullable<ReturnType<typeof getPreparationTab>>;
}) {
    const { query, active, select } = useActivePreparation();
    const navigate = useNavigate();
    const id = tab.preparationId ?? active?.id;
    const detail = usePreparationRecord(tab.preparationId ? undefined : id);
    function switchRecord(nextId: string) {
        if (nextId === id || !allowRecordChange()) return;
        select(nextId);
        if (tab.preparationId) {
            navigate(preparationTabPath(nextId, tab.section));
        }
    }
    return (
        <div data-preparation-workspace className={styles.frame}>
            <PreparationHeader
                preparations={query.data}
                activeId={id}
                onSelect={switchRecord}
            />
            {id && (
                <PreparationNav id={id} overviewIsList={!tab.preparationId} />
            )}
            <div
                key={id + ':' + tab.section}
                className={styles.panel}
                data-preparation-panel
                tabIndex={0}
                role="region"
                aria-label="선택한 준비 탭 내용"
            >
                <State
                    loading={
                        !tab.preparationId &&
                        (query.isPending || (!!id && detail.isPending))
                    }
                    error={query.error ?? detail.error}
                    retry={() => {
                        void query.refetch();
                        if (id && !tab.preparationId) void detail.refetch();
                    }}
                />
                {(tab.preparationId ||
                    (query.isSuccess && (!id || detail.isSuccess))) && (
                    <Outlet context={detail.data} />
                )}
            </div>
            {tab.section === 'conditions' && <ConditionContinue />}
        </div>
    );
}
