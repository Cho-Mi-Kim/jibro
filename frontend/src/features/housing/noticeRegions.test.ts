import { describe, expect, it } from 'vitest';
import { noticeListTitle } from './noticeDisplay';
import {
    noticeRegionQuery,
    recruitmentRegionLabel,
    regionFilterOptions,
} from './noticeRegions';

describe('공고 모집 지역', () => {
    it.each([
        [
            '[정정공고][인천지역본부] 일반 매입임대',
            '[정정][인천] 일반 매입임대',
        ],
        [
            '[전북지역본부]2026년 2차 군산시 분양전환형',
            '[전북]2차 군산시 분양전환형',
        ],
        ['2차 대구경북지역 든든전세주택', '2차 대구경북 든든전세주택'],
        [
            '[정정]2차 대구경북지역 든든전세주택',
            '[정정]2차 대구경북 든든전세주택',
        ],
        ['원주시지역 영구임대', '원주시 영구임대'],
        ['아산 · 천안지역 국민임대', '아산 · 천안 국민임대'],
        ['[정정]증평군지역 국민임대', '[정정]증평군 국민임대'],
        ['[제주지역본부-제주시] 든든전세', '[제주시] 든든전세'],
        ['[제주 - 제주시] 든든전세', '[제주시] 든든전세'],
        ['[제주시-서귀포시] 든든전세', '[제주시-서귀포시] 든든전세'],
        ['영암,해남 지역 국민임대', '영암,해남 국민임대'],
        ['경북서부지역(구미시, 김천시)', '경북서부(구미시, 김천시)'],
        ['인구감소지역 지역 우선공급', '인구감소지역 지역 우선공급'],
    ])('지명 뒤의 지역·지역본부만 생략한다: %s', (title, expected) => {
        expect(noticeListTitle(title)).toBe(expected);
    });

    it('과거 공고 제목의 시 표기와 붙여 쓴 지명을 줄이고 역명·단지명은 유지한다', () => {
        expect(noticeListTitle('인천시 남동구 국민임대')).toBe(
            '인천 남동구 국민임대',
        );
        expect(noticeListTitle('울산광역시지역 국민임대')).toBe(
            '울산 국민임대',
        );
        expect(noticeListTitle('광주광역시국민임대예비자모집')).toBe(
            '전남광주 국민임대예비자모집',
        );
        expect(noticeListTitle('인천시청역 제주도련 광주시')).toBe(
            '인천시청역 제주도련 광주시',
        );
    });
    it('요약 제목의 광역 지명은 줄이고 시군명과 기관명은 보존한다', () => {
        expect(noticeListTitle('[전남광주통합특별시 북구] 매입임대')).toBe(
            '[전남광주 북구] 매입임대',
        );
        expect(noticeListTitle('경기도 광주시 경기도시공사')).toBe(
            '경기 광주시 경기도시공사',
        );
    });
    it('공식 명칭과 과거 명칭을 하나의 선택지로 묶고 정해진 순서로 표시한다', () => {
        expect(
            regionFilterOptions([
                '전라남도',
                '전국',
                '대구광역시 외',
                '광주광역시',
                '강원도',
                '강원특별자치도',
            ]),
        ).toEqual(['강원', '대구', '전국', '전남광주']);
        expect(recruitmentRegionLabel('경상북도·대구광역시')).toBe('경북·대구');
        expect(recruitmentRegionLabel('광주광역시·전라남도')).toBe('전남광주');
        expect(recruitmentRegionLabel('경기도 외')).toBe('경기 외');
    });

    it('전남광주 선택과 예전 광주 주소가 같은 모집 지역을 조회한다', () => {
        const query = new URLSearchParams(
            noticeRegionQuery('region=전남광주&status=OPEN'),
        );
        expect(query.get('status')).toBe('OPEN');
        expect(query.getAll('region')).toEqual(
            expect.arrayContaining([
                '광주광역시',
                '전라남도',
                '전남광주통합특별시',
            ]),
        );
        expect(noticeRegionQuery('region=광주광역시&status=OPEN')).toBe(
            query.toString(),
        );
    });
});
