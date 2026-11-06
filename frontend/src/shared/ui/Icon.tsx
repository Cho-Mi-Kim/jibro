export type IconName =
    | 'home'
    | 'file'
    | 'check'
    | 'book'
    | 'user'
    | 'bell'
    | 'settings'
    | 'search'
    | 'arrow'
    | 'back'
    | 'down'
    | 'calendar'
    | 'external'
    | 'bookmark'
    | 'info'
    | 'warning'
    | 'people'
    | 'phone'
    | 'clock'
    | 'logout'
    | 'pin'
    | 'building'
    | 'share'
    | 'star';
const paths: Record<IconName, string> = {
    home: 'M3 10 12 3l9 7v11h-7v-7h-4v7H3Z',
    file: 'M5 3h9l5 5v13H5ZM14 3v6h5M9 13h6M9 17h5',
    check: 'm5 12 5 5L20 7',
    book: 'M12 5C8 2 5 3 2 4v16c4-2 7-1 10 1 3-2 6-3 10-1V4c-3-1-6-2-10 1Zm0 0v16',
    user: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a8 8 0 0 1 16 0v2',
    bell: 'M18 8a6 6 0 0 0-12 0c0 8-3 8-3 10h18c0-2-3-2-3-10ZM10 21h4',
    settings:
        'M10 2h4l.5 2.3 1.4.6 2.1-1.2 2.3 2.3-1.2 2.1.6 1.4L22 10v4l-2.3.5-.6 1.4 1.2 2.1-2.3 2.3-2.1-1.2-1.4.6L14 22h-4l-.5-2.3-1.4-.6L6 20.3 3.7 18l1.2-2.1-.6-1.4L2 14v-4l2.3-.5.6-1.4L3.7 6 6 3.7l2.1 1.2 1.4-.6Z M15.5 12a3.5 3.5 0 1 1-7 0 3.5 3.5 0 1 1 7 0Z',
    search: 'M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Zm-2 5 6 6',
    arrow: 'm9 5 7 7-7 7',
    back: 'm14 4-8 8 8 8',
    down: 'm5 9 7 7 7-7',
    calendar: 'M4 5h16v16H4ZM8 2v6M16 2v6M4 10h16m-11 5 2 2 4-4',
    external: 'M14 3h7v7m0-7L11 13M10 5H4v16h16v-7',
    bookmark: 'M6 3h12v18l-6-4-6 4Z',
    info: 'M12 17v-5m0-5v1M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
    warning: 'M12 6v8m0 3v1M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
    people: 'M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM2 21v-2a8 8 0 0 1 16 0v2M18 4a4 4 0 0 1 0 7m2 4a7 7 0 0 1 2 6',
    phone: 'M7 3 3 5c-1 8 8 17 16 16l2-4-6-4-2 3-5-5 3-2Z',
    clock: 'M12 5v7l5 3M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
    logout: 'M9 3H3v18h6m6-14 5 5-5 5M8 12h12',
    pin: 'M19 9c0 6-7 12-7 12S5 15 5 9a7 7 0 0 1 14 0ZM14 9a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z',
    building: 'M5 21V3h14v18M9 7h1m4 0h1M9 11h1m4 0h1m-5 10v-6h4v6',
    share: 'M12 15V3m-4 4 4-4 4 4M4 13v7h16v-7',
    star: 'm12 2 3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1Z',
};
export function Icon({
    name,
    className,
}: {
    name: IconName;
    className?: string;
}) {
    return (
        <svg
            className={className}
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
        >
            <path d={paths[name]} />
        </svg>
    );
}
