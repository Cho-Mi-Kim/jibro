import { Link } from 'react-router';
import type { User } from '../housing/types';
import { cx } from '../../shared/styles/classes';
import { Icon } from '../../shared/ui/Icon';
import s from './MyProfileSummary.module.css';

interface Props {
    user: User | null;
    ongoing?: number;
    archived?: number;
}

export function MyProfileSummary({ user, ongoing, archived }: Props) {
    return (
        <section className={s.summary} aria-labelledby="my-profile-name">
            <div className={s.identity}>
                <span
                    className={s.avatar}
                    data-visitor={user?.trial}
                    aria-hidden="true"
                >
                    {user?.trial || !user?.name ? (
                        <Icon name="user" />
                    ) : (
                        user.name.slice(0, 1)
                    )}
                </span>
                <div className={s.nameBlock}>
                    <h2 id="my-profile-name">
                        {user
                            ? user.trial
                                ? '방문자님'
                                : user.name + '님'
                            : '로그인하고 준비를 이어가세요'}
                    </h2>
                    {!user && (
                        <div className={s.entryLinks}>
                            <Link
                                className={cx('link-button')}
                                to="/login?returnTo=%2Fmy"
                            >
                                로그인
                            </Link>
                            <Link
                                className={cx('link-button outline-button')}
                                to="/signup?returnTo=%2Fmy"
                            >
                                회원가입
                            </Link>
                        </div>
                    )}
                </div>
            </div>
            {user && (
                <nav className={s.counts} aria-label="나의 준비 현황">
                    {(
                        [
                            ['현재 준비', ongoing, '/preparations', 'file'],
                            ['보관한 여정', archived, '/archive', 'check'],
                        ] as const
                    ).map(([label, count, path, icon]) => (
                        <Link key={path} to={path} className={s.stat}>
                            <Icon name={icon} className={s.statIcon} />
                            <span className={s.label}>{label}</span>
                            <strong className={s.value}>
                                {count ?? '—'}
                                <span className={s.unit}>건</span>
                            </strong>
                        </Link>
                    ))}
                </nav>
            )}
        </section>
    );
}
