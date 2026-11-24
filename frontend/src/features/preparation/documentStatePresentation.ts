import type { IconName } from '../../shared/ui/Icon';
import type { DocumentState } from '../housing/types';

export const documentStatePresentation = {
    UNCHECKED: { label: '미확인', icon: 'info', tone: 'gray' },
    NEEDED: { label: '발급 필요', icon: 'file', tone: 'red' },
    IN_PROGRESS: { label: '준비 중', icon: 'clock', tone: 'gray' },
    READY: { label: '준비 완료', icon: 'check', tone: 'green' },
    RECHECK: { label: '재확인', icon: 'warning', tone: 'amber' },
} as const satisfies Record<
    DocumentState,
    { label: string; icon: IconName; tone: string }
>;
