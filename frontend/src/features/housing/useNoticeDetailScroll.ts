import { useScrollToCard } from '../../shared/ui/useScrollToCard';

export function useNoticeDetailScroll<T extends HTMLElement = HTMLElement>() {
    return useScrollToCard<T>('[data-notice-detail-scroll]');
}
