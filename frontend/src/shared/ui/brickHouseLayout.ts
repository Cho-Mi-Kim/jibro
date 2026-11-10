// Bottom-to-top courses, staggered by half a brick and trimmed at the wall edges.
// These dimensions describe the illustration only, not the preparation rules.
const left = 44;
const wallWidth = 140;
const pitch = 35;

export const wallBricks = Array.from({ length: 6 }, (_, row) =>
    Array.from({ length: row % 2 === 0 ? 4 : 5 }, (_, column) => {
        const start = left + column * pitch - (row % 2) * (pitch / 2);
        const x = Math.max(left, start);
        return {
            id: `${row}-${column}`,
            rect: {
                x,
                y: 150 - row * 15,
                width: Math.min(left + wallWidth, start + pitch - 1.5) - x,
                height: 13.5,
            },
            tint: [0.04, 0.16, 0, 0.1, 0.21][(row * 3 + column) % 5],
        };
    }),
).flat();
