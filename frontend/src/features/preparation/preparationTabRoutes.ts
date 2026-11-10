import { matchPath, type Location } from 'react-router';

export const preparationTabs = [
    ['', '개요'],
    ['conditions', '조건'],
    ['documents', '서류'],
    ['schedule', '일정'],
] as const;
type Section = (typeof preparationTabs)[number][0];

export function preparationTabPath(id: string, section: Section) {
    return (
        '/preparations/' +
        encodeURIComponent(id) +
        (section ? '/' + section : '')
    );
}

export function getPreparationTab({ pathname }: Pick<Location, 'pathname'>) {
    const match = matchPath(
        '/preparations/:preparationId?/:section?',
        pathname,
    );
    if (!match) return null;
    const { preparationId, section = '' } = match.params;
    const tab = preparationTabs.find(([key]) => key === section);
    if (!tab) return null;
    return { preparationId, section: tab[0] };
}
