import { useNoticeDetailReturn } from '../housing/NoticeListVisit';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { getQuestionStep } from './questionNavigation';
import { answerableQuestions } from '../housing/questions';
import { usePreparation } from './usePreparation';
import { usePreparationRecord } from './usePreparationRecord';
import { State } from '../../shared/ui/ui';
import { allowRecordChange } from '../../shared/ui/useUnsavedChanges';
import { PreparationHeader } from './PreparationHeader';
import { PreparationNav } from './PreparationNav';
import { getPreparationTab, preparationTabPath } from './preparationTabRoutes';
import { useActivePreparation } from './useActivePreparation';
import styles from './PreparationHeader.module.css';
import { applicantInputQuestions } from '../housing/preparationApplicability';
import { showFollowUp } from '../housing/followUp';

export function PreparationTabLayout() {
    const tab = getPreparationTab(useLocation());
    return tab ? <Workspace tab={tab} /> : <Outlet />;
}

function ConditionContinue() {
    const { data: record } = usePreparation();
    const questions = answerableQuestions(
        record ? applicantInputQuestions(record) : [],
        Object.values(record?.documentConditions ?? {}),
    );
    if (!record || record.guideState !== 'AVAILABLE' || !questions.length)
        return null;
    const next =
        questions[
            getQuestionStep(
                { questions, answers: record.answers },
                undefined,
                record.questions,
            )
        ];
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
    const current = query.data?.find((record) => record.id === id);
    const detail = usePreparationRecord(id, current?.revision);
    const detailReturn = useNoticeDetailReturn(current?.noticeId);
    function switchRecord(nextId: string) {
        if (nextId === id || !allowRecordChange()) return;
        select(nextId);
        if (tab.preparationId) {
            navigate(preparationTabPath(nextId, tab.section));
        }
    }
    return (
        <div data-preparation-workspace className={styles.frame}>
            {current && detailReturn && (
                <Link className={styles.detailBack} {...detailReturn}>
                    공고 상세로 돌아가기
                </Link>
            )}
            <PreparationHeader
                preparations={query.data}
                activeId={id}
                onSelect={switchRecord}
            />
            {id && (
                <PreparationNav
                    id={id}
                    overviewIsList={!tab.preparationId}
                    followUpVisible={showFollowUp(detail.data)}
                />
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
