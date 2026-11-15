package org.jibro.collection.review;

import org.jibro.notice.HousingModels.Evidence;

/** A source-bound review finding, not a user's qualification result. */
public record GuidanceFinding(String code, String message, Evidence evidence) {}
