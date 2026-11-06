import { Link } from 'react-router';
import { usePreparation } from '../features/preparation/usePreparation';
import { journeySteps } from '../features/preparation/journey';
import { useNotice } from '../shared/ui/housingHelpers';
import { OfficialLink } from '../shared/ui/HousingUI';
import { Card, Heading, State } from '../shared/ui/ui';
import styles from '../features/preparation/Workflow.module.css';
export function JourneyPage() {
    const q = usePreparation();
    const p = q.data;
    const notice = useNotice(p?.noticeId);
    return (
        <>
            <Heading title="전체 준비 여정" backTo="/">
                선택한 공고의 단계별 기록을 확인하세요.
            </Heading>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <Card>
                        <h2>{p.title}</h2>
                        <p>준비 안내 v{p.version}</p>
                        <ol className={styles.steps}>
                            {journeySteps(p).map((step) => (
                                <li key={step.label}>
                                    <Link to={step.path}>
                                        <strong>{step.label}</strong>
                                        <span>{step.description}</span>
                                    </Link>
                                </li>
                            ))}
                        </ol>
                    </Card>
                    <Card>
                        <h2>공식 신청 안내</h2>
                        <p>
                            신청과 결과 확인은 운영기관에서 진행합니다. 원문
                            열람은 신청 완료로 기록되지 않습니다.
                        </p>
                        <State
                            loading={notice.isPending}
                            error={notice.error}
                            retry={() => void notice.refetch()}
                        />
                        {notice.isSuccess && (
                            <OfficialLink url={notice.data.evidence.url}>
                                운영기관 공식 공고 열기
                            </OfficialLink>
                        )}
                    </Card>
                </>
            )}
        </>
    );
}
