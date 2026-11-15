import { createElement, type HTMLAttributes } from 'react';
import { usePreparationCardSelection } from './usePreparationCardSelection';

export function PreparationScrollSection(
    props: HTMLAttributes<HTMLElement> & {
        as?: 'section' | 'article' | 'div' | 'details' | 'aside';
    },
) {
    const { as: Element = 'section', ...attributes } = props;
    const { cardRef, onClick } = usePreparationCardSelection();
    return createElement(Element, { ...attributes, ref: cardRef, onClick });
}
