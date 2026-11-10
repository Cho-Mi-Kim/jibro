import type { Notice, Preparation } from '../housing/types';
import { Icon } from '../../shared/ui/Icon';
import { EvidenceLink } from '../../shared/ui/ui';
import { displayDate } from '../../shared/ui/housingHelpers';
import { buildScheduleTimeline } from './scheduleTimelineModel';
import styles from './ScheduleTimeline.module.css';

export function ScheduleTimeline({
    notice,
    preparation,
}: {
    notice?: Notice;
    preparation: Preparation;
}) {
    const timeline = buildScheduleTimeline(notice, preparation);
    return (
        <section id="timeline" tabIndex={-1} className={styles.card}>
            <header className={styles.header}>
                <h2>전체 일정</h2>
            </header>
            <ol className={styles.list}>
                {timeline.rows.map((row) => (
                    <li
                        key={row.id}
                        className={styles.row}
                        data-tone={row.tone}
                    >
                        <span className={styles.marker} aria-hidden="true">
                            {row.tone === 'done' && <Icon name="check" />}
                            {row.tone === 'active' && <Icon name="pin" />}
                        </span>
                        <div className={styles.date}>
                            {row.date ? (
                                <time dateTime={row.date}>
                                    {row.dateLabel}
                                    <small>({row.weekday})</small>
                                    {row.time && <small>{row.time}</small>}
                                </time>
                            ) : (
                                <span aria-label="날짜 확인 필요">—</span>
                            )}
                        </div>
                        <strong className={styles.title}>{row.title}</strong>
                        <span className={styles.badge}>{row.status}</span>
                    </li>
                ))}
            </ol>
            <details className={styles.sources}>
                <summary aria-label="일정 근거와 안내 펼치기">
                    <Icon name="down" />
                </summary>
                {timeline.checkedAt && (
                    <p className={styles.checked}>
                        공식 자료 확인 · {displayDate(timeline.checkedAt)}
                    </p>
                )}
                <h3 className={styles.sourcesTitle}>
                    일정 근거 · 세부 접수 일정 보기
                </h3>
                {timeline.evidence && (
                    <EvidenceLink
                        evidence={timeline.evidence}
                        className={styles.evidence}
                    />
                )}
                {timeline.details.map((event) => (
                    <div key={event.id} className={styles.source}>
                        <h3>{event.title}</h3>
                        <p>{displayDate(event.date)}</p>
                        {event.recorded && <p>내 기록 · 완료</p>}
                        <EvidenceLink
                            evidence={event.evidence}
                            className={styles.evidence}
                        />
                    </div>
                ))}
                {!timeline.evidence && !timeline.details.length && (
                    <p>
                        확인된 일정 근거가 없습니다. 공식 공고를 확인해 주세요.
                    </p>
                )}
                <p className={styles.note}>
                    공고에 따라 일부 단계가 없거나 일정이 다를 수 있어요.
                    확인되지 않은 날짜와 마감 시각은 공고 원문에서 확인해
                    주세요. 일정 경과와 실제 신청 완료는 별개입니다.
                </p>
            </details>
        </section>
    );
}
