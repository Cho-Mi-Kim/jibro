package org.jibro.collection;

import java.util.UUID;

/**
 * Internal collection follow-ups; unverified drafts or relationships must stay private.
 */
public interface CollectionItemProcessor {
    void process(UUID noticeId, LhNoticeClient.Item item);
}
