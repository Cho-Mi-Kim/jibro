import { cx } from '../styles/classes';
import { Icon, type IconName } from './Icon';
export function Disc({
    name,
    tone = 'red',
}: {
    name: IconName;
    tone?: string;
}) {
    return (
        <span className={cx('icon-disc')} data-tone={tone}>
            <Icon name={name} />
        </span>
    );
}
