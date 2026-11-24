const regionNames: Record<string, string[]> = {
    강원: ['강원특별자치도', '강원도'],
    경기: ['경기도'],
    경남: ['경상남도'],
    경북: ['경상북도'],
    대구: ['대구광역시', '대구시'],
    대전: ['대전광역시', '대전시'],
    부산: ['부산광역시', '부산시'],
    서울: ['서울특별시', '서울시'],
    세종: ['세종특별자치시', '세종시'],
    울산: ['울산광역시', '울산시'],
    인천: ['인천광역시', '인천시'],
    전국: [],
    전남광주: ['전남광주통합특별시', '전라남도', '광주광역시', '전남', '광주'],
    전북: ['전북특별자치도', '전라북도'],
    제주: ['제주특별자치도', '제주도'],
    충남: ['충청남도'],
    충북: ['충청북도'],
};

const labels = new Map<string, string>();
for (const [label, aliases] of Object.entries(regionNames)) {
    for (const name of [label, ...aliases]) labels.set(name, label);
}

export function regionFilterLabel(value: string) {
    const base = value.trim().replace(/ 외$/, '');
    return labels.get(base) ?? base;
}

// Historical titles sometimes join a province to 지역 or a housing type without a space.
export function shortenProvinceNames(text: string) {
    return text.replace(/[가-힣]+/g, (word) => {
        if (word.length <= 2) return word;
        const label = labels.get(word);
        if (label) return label;
        const joined = /^(.+?)(지역|(?:국민|영구|공공|매입|전세)임대.*)$/.exec(
            word,
        );
        const province = joined && labels.get(joined[1]);
        return joined && province && joined[1].length > 2
            ? province + ' ' + joined[2]
            : word;
    });
}

// 모집 제목에는 시·군을 뺀 지명도 쓰인다. '신규지역' 같은 일반 명사는 보존한다.
const municipalityNames = new Set(
    (
        '수원 성남 의정부 안양 부천 광명 평택 동두천 안산 고양 과천 구리 남양주 오산 시흥 군포 의왕 하남 용인 파주 이천 안성 김포 화성 광주 양주 포천 여주 연천 가평 양평 강화 옹진 ' +
        '춘천 원주 강릉 동해 태백 속초 삼척 홍천 횡성 영월 평창 정선 철원 화천 양구 인제 고성 양양 ' +
        '청주 충주 제천 보은 옥천 영동 증평 진천 괴산 음성 단양 천안 공주 보령 아산 서산 논산 계룡 당진 금산 부여 서천 청양 홍성 예산 태안 ' +
        '전주 군산 익산 정읍 남원 김제 완주 진안 무주 장수 임실 순창 고창 부안 목포 여수 순천 나주 광양 담양 곡성 구례 고흥 보성 화순 장흥 강진 해남 영암 무안 함평 영광 장성 완도 진도 신안 ' +
        '포항 경주 김천 안동 구미 영주 영천 상주 문경 경산 의성 청송 영양 영덕 청도 고령 성주 칠곡 예천 봉화 울진 울릉 달성 군위 ' +
        '창원 진주 통영 사천 김해 밀양 거제 양산 의령 함안 창녕 남해 하동 산청 함양 거창 합천 기장 울주 제주 서귀포'
    ).split(' '),
);
const provinceArea = new RegExp(
    '^(?:' + [...labels.keys()].join('|') + ')+(?:동부|서부|남부|북부|중부)?$',
);

export function shortTitleRegions(title: string) {
    const withoutSuffix = title.replace(
        /([가-힣]+?)\s*지역(?:\s*본부)?(?=$|[\s()[\]·,./_-])/g,
        (match, name: string) =>
            match.endsWith('본부') ||
            provinceArea.test(name) ||
            municipalityNames.has(name) ||
            /^[가-힣]{2,}(?:시|군|구|읍|면|동)$/.test(name)
                ? name
                : match,
    );
    return shortenProvinceNames(
        withoutSuffix.replace(/\[\s*([가-힣]+)\s*[-–]\s*\1시\s*\]/g, '[$1시]'),
    );
}

export function regionFilterOptions(values: string[]) {
    const available = new Set(values.map(regionFilterLabel));
    const ordered = Object.keys(regionNames).filter((label) =>
        available.delete(label),
    );
    return [...ordered, ...available];
}

export function recruitmentRegionLabel(value: string) {
    const parts = value.split(/\s*[·,/]\s*/).map((part) => {
        const name = part.trim();
        const outside = name.endsWith(' 외');
        return (
            shortenProvinceNames(regionFilterLabel(name)) +
            (outside ? ' 외' : '')
        );
    });
    return [...new Set(parts.filter(Boolean))].join('·');
}

export function noticeRegionQuery(query: string) {
    const params = new URLSearchParams(query);
    if (!params.has('region')) return query;
    const selected = new Set(params.getAll('region').map(regionFilterLabel));
    params.delete('region');
    for (const label of selected) {
        params.append('region', label);
        if (Object.hasOwn(regionNames, label)) {
            for (const alias of regionNames[label])
                params.append('region', alias);
        }
    }
    return params.toString();
}
