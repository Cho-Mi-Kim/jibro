import { cx } from '../styles/classes';

export function HomeNoticeTitle({
    title,
    type,
}: {
    title: string;
    type?: string;
}) {
    return (
        <>
            <span className={cx('home-title-full')}>{title}</span>
            <span className={cx('home-title-short')} aria-hidden="true">
                {type?.trim() || title}
            </span>
        </>
    );
}
