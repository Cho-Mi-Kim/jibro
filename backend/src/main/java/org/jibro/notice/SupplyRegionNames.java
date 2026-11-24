package org.jibro.notice;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Province names as used by the official supply tables and the public notice label.
 */
public final class SupplyRegionNames {
    private record Province(String official, String shortName, String... aliases) {
    }

    private static final List<Province> PROVINCES = List.of(
            new Province("서울특별시", "서울"), new Province("부산광역시", "부산"), new Province("대구광역시", "대구"),
            new Province("인천광역시", "인천"), new Province("전남광주통합특별시", "전남광주", "광주광역시", "전라남도", "전남", "광주"),
            new Province("대전광역시", "대전"), new Province("울산광역시", "울산"), new Province("세종특별자치시", "세종"),
            new Province("경기도", "경기"), new Province("강원특별자치도", "강원", "강원도"), new Province("충청북도", "충북"),
            new Province("충청남도", "충남"), new Province("전북특별자치도", "전북", "전라북도"), new Province("경상북도", "경북"),
            new Province("경상남도", "경남"), new Province("제주특별자치도", "제주", "제주도"));
    private static final Map<String, String> ALIASES = aliases();
    private static final Map<String, String> SHORT_NAMES = PROVINCES.stream().collect(Collectors.toMap(Province::official, Province::shortName));

    private SupplyRegionNames() {
    }

    private static Map<String, String> aliases() {
        var result = new LinkedHashMap<String, String>();
        for (var province : PROVINCES) {
            result.put(province.official(), province.official());
            for (var alias : province.aliases()) result.put(alias, province.official());
            result.put(province.shortName(), province.official());
        }
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, String> officialNames() {
        return ALIASES;
    }

    public static String display(String listing, Collection<String> verified) {
        if (verified.contains("전국") || "전국".equals(listing)) return "전국";
        if (verified.isEmpty()) return listing;
        var names = new LinkedHashSet<String>();
        String primary = ALIASES.get(listing == null ? "" : listing.replaceFirst(" 외$", ""));
        if (primary != null) names.add(primary);
        for (var province : PROVINCES) {
            if (verified.contains(province.official())) names.add(province.official());
        }
        if (names.size() < 2) return listing;
        return names.stream().map(SHORT_NAMES::get).collect(Collectors.joining("·"));
    }
}
