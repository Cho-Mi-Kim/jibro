import { useId } from 'react';
export function BrickHouse({ stage = 3 }: { stage?: number }) {
    const id = useId().replace(/:/g, '');
    return (
        <svg
            viewBox="0 0 260 190"
            role="img"
            aria-label={'집 건축 ' + stage + '단계'}
        >
            <defs>
                <linearGradient id={id + 'brick'} x2="1" y2="1">
                    <stop stopColor="#e58d69" />
                    <stop offset="1" stopColor="#b43f27" />
                </linearGradient>
                <linearGradient id={id + 'wood'} x2="0" y2="1">
                    <stop stopColor="#ffdbac" />
                    <stop offset="1" stopColor="#e5a368" />
                </linearGradient>
            </defs>
            <ellipse cx="132" cy="172" rx="123" ry="12" fill="#f4eee7" />
            <path
                d="M20 168v-8a7 7 0 0 1 9-6 10 10 0 0 1 18-10 9 9 0 0 1 9 10v14"
                fill="#c5cea6"
            />
            <path
                d="M60 166V80l64-55 63 55v86Z"
                fill="#faf4ee"
                stroke="#eaded4"
            />
            {Array.from({ length: 5 }, (_, row) =>
                Array.from({ length: 4 }, (_, col) => {
                    const complete =
                        row >= 5 - Math.max(1, stage + 1) &&
                        !(stage < 4 && col === 2 && row < 3);
                    return (
                        <rect
                            key={row + '-' + col}
                            x={61 + col * 31}
                            y={82 + row * 17}
                            width="29"
                            height="15"
                            rx="1.5"
                            fill={complete ? 'url(#' + id + 'brick)' : '#fff'}
                            opacity={complete ? 1 : 0.7}
                        />
                    );
                }),
            )}
            {stage >= 3 && (
                <>
                    <rect
                        x="160"
                        y="20"
                        width="18"
                        height="43"
                        rx="2"
                        fill={'url(#' + id + 'brick)'}
                    />
                    <path
                        d="m49 84 76-65 75 65M67 81l58-49 57 49"
                        stroke={'url(#' + id + 'wood)'}
                        strokeWidth="10"
                        strokeLinecap="round"
                        fill="none"
                    />
                    <path
                        d="m52 82 73-62 73 63"
                        stroke="#ffe2bc"
                        strokeWidth="2"
                        fill="none"
                    />
                </>
            )}
            {stage >= 4 && <path d="M111 166v-43h28v43" fill="#a54b31" />}
            {stage >= 5 && (
                <>
                    <path
                        d="M72 105h25v24H72zm81 0h25v24h-25Z"
                        fill="#e4f2ee"
                        stroke="#b96d48"
                        strokeWidth="4"
                    />
                    <circle cx="133" cy="146" r="2" fill="#ffe2bc" />
                </>
            )}
            {[0, 1, 2].map((x) => (
                <rect
                    key={x}
                    x={190 + x * 16}
                    y="157"
                    width="15"
                    height="15"
                    rx="2"
                    fill={x % 2 ? '#d9b298' : '#ecd1bb'}
                />
            ))}
            <rect
                x="202"
                y="140"
                width="28"
                height="15"
                rx="2"
                fill="#e8c5ab"
            />
        </svg>
    );
}
