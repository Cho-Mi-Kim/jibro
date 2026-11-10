import { useId } from 'react';
import { wallBricks } from './brickHouseLayout';
import styles from './BrickHouse.module.css';

/** Illustrates server-provided progress; it does not calculate preparation credit. */
export function BrickHouse({
    progress,
    stage,
}: {
    progress: number;
    stage: number;
}) {
    const id = useId().replace(/:/g, '');
    const value = Number.isFinite(progress)
        ? Math.min(100, Math.max(0, progress))
        : null;
    const built =
        value === null ? 0 : Math.floor((value / 100) * wallBricks.length);
    const paint = (name: string) => `url(#${id}-${name})`;
    return (
        <svg
            className={styles.house}
            viewBox="0 0 260 190"
            role="img"
            aria-label={
                value === null
                    ? '집 준비도 확인 중'
                    : `준비도 ${value}% · 벽돌이 쌓이는 집`
            }
        >
            <defs>
                <linearGradient
                    id={`${id}-brick`}
                    x1="0"
                    y1="0"
                    x2="1"
                    y2="0.7"
                >
                    <stop stopColor="#d37c59" />
                    <stop offset="0.48" stopColor="#bc563b" />
                    <stop offset="1" stopColor="#b64a32" />
                </linearGradient>
                <linearGradient id={`${id}-wood`} x1="0" y1="0" x2="0.8" y2="1">
                    <stop stopColor="#f7d9b1" />
                    <stop offset="0.55" stopColor="#edbd88" />
                    <stop offset="1" stopColor="#dc9f67" />
                </linearGradient>
                <linearGradient id={`${id}-spare`}>
                    <stop stopColor="#f1dfcf" />
                    <stop offset="0.55" stopColor="#e8cbb3" />
                    <stop offset="1" stopColor="#dcb398" />
                </linearGradient>
            </defs>
            <ellipse cx="132" cy="171" rx="125" ry="10" fill="#f4efe7" />
            <path
                d="M17 165v-8a7 7 0 0 1 10-6 9 9 0 0 1 16-9 9 9 0 0 1 11 10v13"
                fill="#c6cfb0"
            />
            <path
                d="M30 165v-12a8 8 0 0 1 15-4v16"
                fill="#b8c39a"
                opacity=".65"
            />
            <path
                d="M43 164V78l71-60 71 60v86Z"
                fill="#fcf9f4"
                stroke="#eee4d9"
                strokeWidth="2"
            />
            <g fill="none" stroke="#e8d4be" strokeWidth="5" opacity=".55">
                <path d="M47 80v84m134-84v84M114 27v136M51 80l63-51 63 51M48 80h134" />
            </g>
            <g>
                {wallBricks.map((brick, index) => (
                    <g key={brick.id}>
                        <rect
                            {...brick.rect}
                            rx="1.8"
                            fill="#f7eee5"
                            stroke="#eadfd3"
                            strokeWidth=".6"
                        />
                        <g
                            className={styles.brick}
                            opacity={index < built ? 1 : 0}
                            data-built={index < built}
                        >
                            <rect
                                {...brick.rect}
                                rx="1.8"
                                fill={paint('brick')}
                            />
                            <rect
                                {...brick.rect}
                                rx="1.8"
                                fill="#f5c6a0"
                                opacity={brick.tint}
                            />
                            <path
                                d={`M${brick.rect.x + 1.5} ${brick.rect.y + 2}h${brick.rect.width - 3}`}
                                stroke="#f9dcc1"
                                opacity=".35"
                            />
                            <path
                                d={`M${brick.rect.x + brick.rect.width - 3} ${brick.rect.y + 1.5}v${brick.rect.height - 3}`}
                                stroke="#8f3828"
                                opacity=".13"
                                strokeWidth="2"
                            />
                        </g>
                    </g>
                ))}
            </g>
            <g className={styles.structure} opacity={stage >= 3 ? 1 : 0.35}>
                <rect
                    x="144"
                    y="11"
                    width="19"
                    height="41"
                    rx="2.5"
                    fill={paint('brick')}
                />
                <path
                    d="M147 13v31"
                    stroke="#f0ad8b"
                    strokeWidth="3"
                    opacity=".65"
                />
                <path
                    d="m35 81 79-67 80 67M49 77l65-54 65 54"
                    stroke={paint('wood')}
                    strokeWidth="11"
                    strokeLinecap="round"
                    fill="none"
                />
                <path
                    d="m35 78 79-67 80 67M50 74l64-53"
                    stroke="#ffe8c9"
                    strokeWidth="3"
                    strokeLinecap="round"
                    fill="none"
                    opacity=".9"
                />
                <path
                    d="m123 16 67 57"
                    stroke="#f9dcba"
                    strokeWidth="4"
                    strokeLinecap="round"
                    fill="none"
                />
            </g>
            <g fill={paint('spare')}>
                <rect x="186" y="151" width="29" height="17" rx="2" />
                <rect x="217" y="151" width="27" height="17" rx="2" />
                <rect x="198" y="132" width="36" height="17" rx="2" />
            </g>
            <g stroke="#fff6eb" strokeWidth="3" opacity=".65">
                <path d="M207 133v15m18-15v15m-30 5v14m31-14v14m12-14v14" />
            </g>
        </svg>
    );
}
