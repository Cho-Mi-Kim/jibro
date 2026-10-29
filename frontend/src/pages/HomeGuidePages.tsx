import { cx } from '../shared/styles/classes';
import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { api } from '../features/housing/api';
import {
    Badge,
    Card,
    EvidenceLink,
    Heading,
    House,
    Soon,
    State,
} from '../shared/ui/ui';
export function HomePage() {
    const { user, loading, error } = useSession();
    const q = useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user,
    });
    const p = q.data?.[0];
    return (
        <>
            <Heading
                title={
                    user ? '오늘의 준비, 여기서 이어가요' : '나의 첫 집을 향해'
                }
            >
                복잡한 공고부터 필요한 서류까지,
                <br />
                신청 준비를 한곳에서 차근차근.
            </Heading>
            <div className={cx('row')}>
                <Badge>청년 주거지원 준비 노트</Badge>
                <Soon label="알림" />
            </div>
            {loading ? (
                <State loading />
            ) : error ? (
                <State error={error} />
            ) : !user ? (
                <>
                    <Card>
                        <span className={cx('eyebrow')}>START HERE</span>
                        <h2>
                            공고를 찾았다면,
                            <br />
                            이제 준비할 차례예요.
                        </h2>
                        <p>조건을 확인하고 필요한 서류와 일정을 모아보세요.</p>
                        <Link className={cx('link-button')} to="/notices">
                            공고 둘러보기 →
                        </Link>
                    </Card>
                    <div className={cx('intro-grid')}>
                        {[
                            ['01', '공고 확인', '공식 근거와 안내 상태 확인'],
                            [
                                '02',
                                '내 조건 정리',
                                '모르는 항목은 추가 확인으로',
                            ],
                            [
                                '03',
                                '준비 이어가기',
                                '서류와 일정을 하나씩 기록',
                            ],
                        ].map(([n, t, d]) => (
                            <Card key={n}>
                                <span className={cx('step-number')}>{n}</span>
                                <h3>{t}</h3>
                                <p>{d}</p>
                            </Card>
                        ))}
                    </div>
                    <Link to="/guide">처음이라면, 신청 준비 가이드 ↗</Link>
                </>
            ) : (
                <>
                    <State
                        loading={q.isPending}
                        error={q.error}
                        retry={() => void q.refetch()}
                    />
                    {p ? (
                        <>
                            <Card>
                                <Link
                                    className={cx('card-title')}
                                    to={'/preparations/' + p.id}
                                >
                                    {p.title}
                                </Link>
                                <House
                                    progress={p.progress}
                                    stage={p.houseStage}
                                />
                            </Card>
                            <Card>
                                <span className={cx('eyebrow')}>
                                    지금 할 일
                                </span>
                                <h2>{p.nextAction.label}</h2>
                                <p>{p.nextAction.reason}</p>
                                <Link
                                    className={cx('link-button')}
                                    to={p.nextAction.path}
                                >
                                    준비 방법 보기 →
                                </Link>
                            </Card>
                            {p.warnings.length > 0 && (
                                <p className={cx('callout')}>
                                    추가 확인 항목 {p.warnings.length}개가 남아
                                    있어요.
                                </p>
                            )}
                            <Link to="/preparations">전체 준비 건 보기</Link>
                        </>
                    ) : (
                        !q.isPending &&
                        !q.error && (
                            <Card>
                                <h2>첫 신청 준비를 시작해 보세요</h2>
                                <Link
                                    className={cx('link-button')}
                                    to="/notices"
                                >
                                    공고 찾아보기
                                </Link>
                            </Card>
                        )
                    )}
                </>
            )}
        </>
    );
}
export function GuidePage() {
    const [params, setParams] = useSearchParams();
    const q = useQuery({ queryKey: ['guides'], queryFn: api.guides });
    const category = params.get('category');
    return (
        <>
            <Heading title="신청 준비 가이드">
                낯선 용어와 절차를 하나씩 알아보세요.
            </Heading>
            <Soon label="가이드 검색" />
            <div className={cx('tabs')}>
                <button onClick={() => setParams({})} aria-pressed={!category}>
                    전체
                </button>
                {[...new Set(q.data?.map((g) => g.category))].map((c) => (
                    <button
                        key={c}
                        aria-pressed={category === c}
                        onClick={() => setParams({ category: c })}
                    >
                        {c}
                    </button>
                ))}
            </div>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {q.data
                ?.filter((g) => !category || g.category === category)
                .map((g) => (
                    <details className={cx('card')} key={g.id}>
                        <summary>{g.title}</summary>
                        <Badge>{g.category}</Badge>
                        <p>{g.body}</p>
                        <EvidenceLink evidence={g.evidence} />
                    </details>
                ))}
            <p className={cx('muted')}>
                일반 안내와 개별 공고의 조건은 다를 수 있습니다. 선택한 공고의
                공식 원문을 함께 확인해 주세요.
            </p>
        </>
    );
}
