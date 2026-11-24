import { expect, it } from 'vitest';
import { alignedScoreRows } from './alignedScoreRows';

it('유형별 인원 경계를 합치고 각 구간에 원래 점수를 적용한다', () => {
    expect(
        alignedScoreRows([
            [
                { criterion: '1~2명', points: '14점' },
                { criterion: '3명 이상', points: '16점' },
            ],
            [
                { criterion: '1명', points: '1점' },
                { criterion: '2명 이상', points: '2점' },
            ],
        ]),
    ).toEqual([
        ['1명', '14점', '1점'],
        ['2명', '14점', '2점'],
        ['3명 이상', '16점', '2점'],
    ]);
});

it('원문에 없는 점수를 만들지 않고 소득 예외 문구를 보존한다', () => {
    const criterion = '50% 이하 (1인 가구 70%, 2인 가구 60% 이하)';
    expect(alignedScoreRows([[{ criterion, points: '20점' }], []])).toEqual([
        [criterion, '20점', '—'],
    ]);
});
