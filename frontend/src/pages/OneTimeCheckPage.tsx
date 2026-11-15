import {
    Link,
    useLocation,
    useNavigate,
    useParams,
    useSearchParams,
} from 'react-router';
import { useNotice } from '../features/housing/useNotice';
import { Button, Card, Heading, State } from '../shared/ui/ui';
import { startPath } from '../features/preparation/startIntent';
import { OneTimeCheckSession } from '../features/preparation/OneTimeCheckSession';
import { oneTimeCheckPath } from '../features/preparation/oneTimeCheckApi';
import styles from '../features/preparation/OneTimeCheck.module.css';

export function OneTimeCheckPage({ mode }: { mode: 'choice' | 'once' }) {
    const { noticeId = '' } = useParams();
    const [params] = useSearchParams();
    const { state } = useLocation();
    const navigate = useNavigate();
    const query = useNotice(noticeId);
    const notice = query.data;
    const requestedVersion = params.get('version');
    const available =
        notice?.canStart &&
        notice.guideState === 'AVAILABLE' &&
        (!requestedVersion || Number(requestedVersion) === notice.version);
    return (
        <>
            <Heading
                title={
                    mode === 'choice' ? '조건 확인 방법' : '이번만 조건 확인'
                }
                backTo={'/notices/' + noticeId}
            >
                {notice?.title}
            </Heading>
            <State
                loading={query.isPending}
                error={query.error}
                retry={() => void query.refetch()}
            />
            {notice && !available && (
                <Card>
                    <p role="alert">
                        공고 안내가 변경되었거나 조건 확인을 시작할 수 없습니다.
                        상세에서 최신 안내를 확인해 주세요.
                    </p>
                    <Link to={'/notices/' + noticeId}>공고 상세 보기</Link>
                </Card>
            )}
            {notice &&
                available &&
                (mode === 'once' ? (
                    <OneTimeCheckSession
                        key={notice.id + ':' + notice.version}
                        notice={notice}
                    />
                ) : (
                    <div className={styles.choices}>
                        <Card>
                            <h2>저장하고 이어하기</h2>
                            <p>
                                계정에 답변과 준비 상태를 저장하고, 다음에
                                이어서 준비할 수 있어요.
                            </p>
                            <Button
                                onClick={() =>
                                    navigate(
                                        startPath(
                                            notice.id,
                                            notice.version,
                                            params.get('view') === 'home'
                                                ? 'home'
                                                : 'conditions',
                                        ),
                                        { state },
                                    )
                                }
                            >
                                저장하고 이어하기
                            </Button>
                        </Card>
                        <Card>
                            <h2>이번만 확인</h2>
                            <p>
                                로그인 없이 조건과 필요한 서류를 확인해요.
                                답변과 결과는 저장하지 않아요.
                            </p>
                            <Button
                                className="secondary"
                                onClick={() =>
                                    navigate(
                                        oneTimeCheckPath(
                                            notice.id,
                                            notice.version,
                                        ),
                                        { state },
                                    )
                                }
                            >
                                저장하지 않고 이번만 확인
                            </Button>
                        </Card>
                    </div>
                ))}
        </>
    );
}
