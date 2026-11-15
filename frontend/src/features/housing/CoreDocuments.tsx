import { documentSummary } from './documentSummary';
import { DocumentSummaryFacts } from './DocumentSummaryFacts';
import type { Notice } from './types';
import styles from './CoreDocuments.module.css';

export function CoreDocuments({
    kind,
    notice,
    templates = notice.documentTemplates,
}: {
    kind: 'common' | 'conditional';
    templates?: Notice['documentTemplates'];
    notice: Notice;
}) {
    const group = notice.documentSummary?.[kind];
    if (!group?.items.length) return null;
    return (
        <>
            <ul className={styles.coreDocuments}>
                {group.items.map((item) => {
                    const template = templates?.find(
                        (doc) => doc.id === item.id,
                    );
                    const summary = template
                        ? documentSummary(template, notice)
                        : {
                              audience:
                                  kind === 'common'
                                      ? '모든 신청자'
                                      : '공식 공고에서 해당 조건 확인',
                              method: '공식 공고의 발급 안내 확인',
                          };
                    return (
                        <li key={item.id}>
                            <strong>{item.title}</strong>
                            <DocumentSummaryFacts summary={summary} />
                        </li>
                    );
                })}
            </ul>
            {group.remainingCount > 0 && (
                <small>외 {group.remainingCount}개</small>
            )}
        </>
    );
}
