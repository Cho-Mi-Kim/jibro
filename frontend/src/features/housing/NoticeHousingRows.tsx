import { Icon } from '../../shared/ui/Icon';
import { noticeHousingFacts } from './noticeHousingFacts';
import type { Notice } from './types';

export function NoticeHousingRows({
    notice,
}: {
    notice: Pick<Notice, 'region' | 'type'>;
}) {
    return noticeHousingFacts(notice).map(({ label, icon, text }) => (
        <div key={label}>
            <dt>
                <Icon name={icon} />
                {label}
            </dt>
            <dd>{text}</dd>
        </div>
    ));
}
