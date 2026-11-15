package org.jibro.notice;

import tools.jackson.databind.JsonNode;

/** Published facts projection, never a live persistence entity. */
public record NoticeVersion(int version, int revision, JsonNode facts) {}
