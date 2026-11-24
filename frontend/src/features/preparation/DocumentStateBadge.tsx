import { Icon } from '../../shared/ui/Icon';
import { Status } from '../../shared/ui/Status';
import type { DocumentState } from '../housing/types';
import { documentStatePresentation } from './documentStatePresentation';

export function DocumentStateBadge({ state }: { state: DocumentState }) {
    const { label, icon, tone } = documentStatePresentation[state];
    return (
        <Status tone={tone}>
            <Icon name={icon} />
            {label}
        </Status>
    );
}
