package org.jibro.notice;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.core.types.dsl.BooleanExpression;

import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.Collection;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.jibro.notice.HousingModels.Page;
import org.springframework.stereotype.Repository;

@Repository
public class NoticeQueryRepository {
    private static final String OTHER_REGIONS_SUFFIX = " 외";
    private final JPAQueryFactory query;

    private static String filterRegion(String region) {
        return region.endsWith(OTHER_REGIONS_SUFFIX)
            ? region.substring(0, region.length() - OTHER_REGIONS_SUFFIX.length()) : region;
    }

    public NoticeQueryRepository(JPAQueryFactory query) {
        this.query = query;
    }

    Page<NoticeListSource> search(List<String> regions, List<String> types, List<String> statuses, String keyword,
                                  String sort, int page, int size, Instant now) {
        var n = QNoticeEntity.noticeEntity;
        var where = new BooleanBuilder(n.basicReview.eq("PASS").and(n.serviceExcluded.isFalse()));
        where.and(n.sourceKey.isNull().or(n.sourceKey.startsWith("local-sample-").not()));
        where.and(currentListing(n));
        if (statuses.isEmpty()) where.and(DefaultNoticeScope.visible(n, now));
        if (!regions.isEmpty()) {
            var sourceRegions = new LinkedHashSet<String>();
            sourceRegions.add("전국");
            for (String region : regions) {
                String base = filterRegion(region);
                sourceRegions.add(base);
                sourceRegions.add(base + OTHER_REGIONS_SUFFIX);
            }
            where.and(n.region.in(sourceRegions).or(hasVerifiedRegion(n, sourceRegions)));
        }
        if (!types.isEmpty()) where.and(n.supportType.in(types));
        if (!keyword.isBlank())
            where.and(n.title.containsIgnoreCase(keyword).or(n.agency.containsIgnoreCase(keyword)).or(n.region.containsIgnoreCase(keyword)));
        LocalDate today = now.atZone(ApplicationPeriod.SEOUL).toLocalDate();
        var closure = Expressions.stringTemplate("function('jsonb_extract_path_text', {0}, 'applicationClosure')", n.content);
        var closed = closure.isNotNull().or(n.sourceClosed.isTrue()).or(n.serviceClosed.isTrue());
        var officiallyClosed = n.lifecycleState.eq("NORMAL").and(closed);
        var dated = n.startDate.isNotNull().and(n.endDate.isNotNull());
        var started = n.applicationStartAt.loe(now).or(n.applicationStartAt.isNull().and(n.startDate.loe(today)));
        var ended = n.applicationEndAt.loe(now).or(n.applicationEndAt.isNull().and(n.endDate.lt(today)));
        var notStarted = n.applicationStartAt.gt(now).or(n.applicationStartAt.isNull().and(n.startDate.gt(today)));
        var notEnded = n.applicationEndAt.gt(now).or(n.applicationEndAt.isNull().and(n.endDate.goe(today)));
        var normal = n.lifecycleState.eq("NORMAL").and(dated).and(closed.not());
        if (!statuses.isEmpty()) {
            var s = new BooleanBuilder();
            if (statuses.contains("CLOSED")) s.or(officiallyClosed.or(normal.and(ended)));
            if (statuses.contains("UPCOMING")) s.or(normal.and(notStarted).and(notEnded));
            if (statuses.contains("OPEN")) s.or(normal.and(started).and(notEnded));
            if (statuses.contains("UNKNOWN"))
                s.or(n.lifecycleState.eq("NORMAL").and(closed.not()).and(n.startDate.isNull().or(n.endDate.isNull())));
            if (statuses.contains("EARLY_CLOSED")) s.or(n.lifecycleState.eq("EARLY_CLOSED"));
            if (statuses.contains("CANCELLED")) s.or(n.lifecycleState.eq("CANCELLED"));
            where.and(s);
        }
        var stateOrder = new CaseBuilder()
            .when(n.lifecycleState.in("EARLY_CLOSED", "CANCELLED").or(officiallyClosed).or(normal.and(ended))).then(2)
            .when(normal.and(started).and(notEnded)).then(0)
            .when(normal.and(notStarted).and(notEnded)).then(1)
            .otherwise(3);
        // JSONB text extraction treats a missing or JSON-null issue list as SQL NULL.
        // Keep this before pagination; sorting only the returned page hides available guidance.
        var issues = Expressions.stringTemplate("function('jsonb_extract_path_text', {0}, 'reviewIssues')", n.content);
        var guidanceOrder = new CaseBuilder()
            .when(n.changeReview.isTrue()).then(2)
            .when(n.guideReview.eq("PASS").and(issues.isNotNull()).and(issues.ne("[]"))).then(1)
            .when(n.guideReview.eq("PASS")).then(0)
            .when(n.guideReview.eq("UNSUPPORTED")).then(3)
            .otherwise(2);
        OrderSpecifier<?>[] ordering = switch (sort) {
            case "newest" -> new OrderSpecifier<?>[]{n.publishedDate.desc().nullsLast(), n.id.asc()};
            case "deadline" -> new OrderSpecifier<?>[]{n.endDate.asc().nullsLast(), n.id.asc()};
            default ->
                new OrderSpecifier<?>[]{stateOrder.asc(), guidanceOrder.asc(), n.endDate.asc().nullsLast(), n.publishedDate.desc().nullsLast(), n.id.asc()};
        };
        long total = Optional.ofNullable(query.select(n.count()).from(n).where(where).fetchOne()).orElse(0L);
        var hasIssues = issues.isNotNull().and(issues.ne(Expressions.stringTemplate("'[]'")));
        var listing = query.select(n.id, n.title, n.agency, n.region, n.supportType,
                n.startDate, n.endDate, n.publishedDate, n.changeReview, n.guideReview, hasIssues, closed,
                n.collectedAt, n.lifecycleState, n.lifecycleReason, n.lifecycleEvidence,
                n.lifecycleCheckedAt, n.lifecycleRevision, n.applicationStartAt, n.applicationEndAt)
            .from(n).where(where);
        if (regions.stream().anyMatch(region -> !region.equals("전국"))) {
            var nationwide = n.region.eq("전국").or(hasVerifiedRegion(n, List.of("전국")));
            listing.orderBy(new CaseBuilder().when(nationwide).then(1).otherwise(0).asc());
        }
        var items = listing.orderBy(ordering).offset((long) page * size).limit(size).fetch().stream()
            .map(row -> new NoticeListSource(row.get(n.id), row.get(n.title), row.get(n.agency),
                row.get(n.region), row.get(n.supportType), row.get(n.startDate), row.get(n.endDate), row.get(n.publishedDate),
                Boolean.TRUE.equals(row.get(n.changeReview)), row.get(n.guideReview), Boolean.TRUE.equals(row.get(hasIssues)),
                Boolean.TRUE.equals(row.get(closed)), row.get(n.collectedAt), row.get(n.lifecycleState), row.get(n.lifecycleReason),
                row.get(n.lifecycleEvidence), row.get(n.lifecycleCheckedAt), row.get(n.lifecycleRevision),
                row.get(n.applicationStartAt), row.get(n.applicationEndAt))).toList();
        return new Page<>(items, page, size, total, (total + size - 1) / size);
    }

    private BooleanExpression hasVerifiedRegion(QNoticeEntity n, Collection<String> regions) {
        var supply = QNoticeSupplyRegionEntity.noticeSupplyRegionEntity;
        return JPAExpressions.selectOne().from(supply)
            .where(supply.noticeId.eq(n.id), supply.sourceFingerprint.eq(n.sourceFingerprint), supply.region.in(regions)).exists();
    }

    Map<UUID,List<String>> verifiedRegions(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var n = QNoticeEntity.noticeEntity;
        var scope = QNoticeSupplyRegionEntity.noticeSupplyRegionEntity;
        var rows = query.select(scope.noticeId, scope.region).from(scope, n)
            .where(scope.noticeId.in(ids), scope.noticeId.eq(n.id), scope.sourceFingerprint.eq(n.sourceFingerprint)).fetch();
        var result = new HashMap<UUID,List<String>>();
        for (var row : rows) result.computeIfAbsent(row.get(scope.noticeId), key -> new ArrayList<>()).add(row.get(scope.region));
        return result;
    }

    public Map<String, List<String>> filters() {
        var n = QNoticeEntity.noticeEntity;
        var visible = n.basicReview.eq("PASS").and(n.serviceExcluded.isFalse()).and(n.sourceKey.isNull().or(n.sourceKey.startsWith("local-sample-").not()))
            .and(currentListing(n));
        var regions = query.select(n.region).distinct().from(n).where(visible).orderBy(n.region.asc()).fetch();
        var options = new TreeSet<String>();
        regions.forEach(region -> options.add(filterRegion(region)));
        var supply = QNoticeSupplyRegionEntity.noticeSupplyRegionEntity;
        options.addAll(query.select(supply.region).distinct().from(n,supply).where(visible,
            supply.noticeId.eq(n.id), supply.sourceFingerprint.eq(n.sourceFingerprint)).fetch());
        return Map.of("regions", List.copyOf(options),
            "types", query.select(n.supportType).distinct().from(n).where(visible).orderBy(n.supportType.asc()).fetch());
    }

    /**
     * Official notice identities remain related when guidance/content revisions change.
     * A hidden replacement must not remove the only readable listing. No title similarity is used.
     */
    private BooleanExpression currentListing(QNoticeEntity original) {
        var link = new QNoticeCorrectionEntity("listingCorrection");
        var replacement = new QNoticeEntity("listingReplacement");
        return JPAExpressions.selectOne().from(link, replacement)
            .where(link.originalId.eq(original.id), link.correctedId.eq(replacement.id),
                replacement.basicReview.eq("PASS"), replacement.serviceExcluded.isFalse(), replacement.sourceKey.startsWith("LH:"))
            .notExists();
    }

}
