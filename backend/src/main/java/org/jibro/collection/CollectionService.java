package org.jibro.collection;

import static org.jibro.notice.HousingModels.*;

import java.time.*;
import java.util.List;
import java.util.UUID;

import org.jibro.notice.NoticeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CollectionService {
    private final JdbcTemplate db;
    private final LhNoticeClient client;
    private final NoticeService notices;
    private final int maxPages;

    public CollectionService(JdbcTemplate db, LhNoticeClient client, NoticeService notices, @Value("${app.collection.max-pages:5}") int maxPages) {
        this.db = db;
        this.client = client;
        this.notices = notices;
        this.maxPages = Math.max(1, Math.min(20, maxPages));
    }

    public List<Collection> list() {
        return db.query("SELECT id,status,started_at,message FROM collection_run ORDER BY started_at DESC LIMIT 100", (rs, n) ->
                new Collection(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant().toString(), rs.getString(4)));
    }

    public synchronized Collection collect(String actor, int startPage) {
        if (startPage < 1 || startPage > 9999) throw new IllegalArgumentException();
        UUID id = UUID.randomUUID();
        String now = Instant.now().toString();
        db.update("INSERT INTO collection_run(id,status,message,actor_id) VALUES (?,?,?,?)", id, "RUNNING", "공식 목록 수집 중", UUID.fromString(actor));
        String status = "SUCCEEDED", message;
        int count = 0;
        if (!client.configured()) {
            status = "NOT_CONFIGURED";
            message = "LH_SERVICE_KEY가 없어 외부 수집을 실행하지 않았습니다.";
        } else try {
            boolean more = false;
            for (int page = startPage; page < startPage + maxPages; page++) {
                var batch = client.fetch(page);
                for (var item : batch.items()) {
                    notices.importSource(item);
                    count++;
                }
                more = batch.total() > page * 100 || batch.total() == 0 && batch.items().size() == 100;
                if (!more) break;
            }
            status = more ? "PARTIAL" : "SUCCEEDED";
            message = count + "건 처리. 신규·변경 공고는 검수 대기입니다." + (more ? " 실행당 페이지 한도에 도달했습니다. 수집 범위를 조정해 이어서 확인하세요." : "");
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            status = "FAILED";
            message = "공식 API 호출 또는 응답 검증에 실패했습니다. " + count + "건 처리 후 중단했습니다. 승인 키·이용 한도·제공자 상태를 확인하세요.";
        }
        db.update("UPDATE collection_run SET status=?,message=? WHERE id=?", status, message, id);
        return new Collection(id.toString(), status, now, message);
    }
}
