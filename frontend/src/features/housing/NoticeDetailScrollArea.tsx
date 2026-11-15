import type { HTMLAttributes } from 'react';
import { useNoticeDetailSelection } from './useNoticeDetailSelection';

export function NoticeDetailScrollSection(props: HTMLAttributes<HTMLElement>) {
    const { cardRef, onClick } = useNoticeDetailSelection<HTMLElement>();
    return <section {...props} ref={cardRef} onClick={onClick} />;
}

export function NoticeDetailScrollDetails(
    props: HTMLAttributes<HTMLDetailsElement>,
) {
    const { cardRef, onClick } = useNoticeDetailSelection<HTMLDetailsElement>();
    return <details {...props} ref={cardRef} onClick={onClick} />;
}
