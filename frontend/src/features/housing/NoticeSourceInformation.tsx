import { Icon } from '../../shared/ui/Icon';
import { OfficialLink } from '../../shared/ui/OfficialLink';
import { guideLabels } from './guideLabels';
import type { SourceInformationArea } from './noticeSourceInformationApi';
import type { NoticeSourceInformationState } from './useNoticeSourceInformation';
import type { Notice } from './types';
import styles from './NoticeSourceInformation.module.css';
import { NoticeDetailScrollDetails } from './NoticeDetailScrollArea';

const areaLabels: Record<SourceInformationArea, string> = {
    CONDITION: '조건',
    DOCUMENT: '서류',
    SCHEDULE: '일정',
    REFERENCE: '도면',
};

function readingLabel(label: string) {
    return label
        .replace(
            /HWP BinData\/BIN([0-9a-f]+)\.\w+/gi,
            (_, number: string) => `첨부 이미지 ${Number.parseInt(number, 16)}`,
        )
        .replaceAll('보조 판독(PSM6)', '표 중심 판독')
        .replaceAll('보조 판독(PSM11)', '도면 글자 보조 판독');
}

export function NoticeSourceInformation({
    notice,
    information,
    area,
    showEmpty = true,
}: {
    notice: Notice;
    information?: NoticeSourceInformationState;
    area?: SourceInformationArea;
    showEmpty?: boolean;
}) {
    const reviewing =
        notice.guideState === 'REVIEWING' ||
        notice.guideState === 'CHANGE_REVIEW';
    const sections =
        reviewing &&
        !information?.loading &&
        !information?.failed &&
        information?.data?.noticeId === notice.id
            ? information.data.sections.filter(
                  (section) => !area || section.area === area,
              )
            : [];
    if (!sections.length) {
        if (!showEmpty) return null;
        return (
            <section className={styles.panel}>
                <h2>{guideLabels[notice.guideState]}</h2>
                <p>
                    {reviewing
                        ? `표시할 ${area ? areaLabels[area] + ' ' : ''}원문은 아직 확인 중이에요.`
                        : '검수되지 않은 개인별 안내는 제공하지 않습니다.'}{' '}
                    세부 기준은 공식 공고에서 확인해 주세요.
                </p>
                {information?.loading && (
                    <p role="status">확인할 수 있는 원문을 불러오고 있어요.</p>
                )}
                <div className={styles.actions}>
                    <OfficialLink url={notice.evidence.url}>
                        공식 공고 보기
                    </OfficialLink>
                    {information?.failed && (
                        <button type="button" onClick={information.retry}>
                            원문 다시 불러오기
                        </button>
                    )}
                </div>
            </section>
        );
    }
    return (
        <section
            className={styles.panel}
            aria-label="공식 원문에서 확인한 내용"
        >
            <div className={styles.heading}>
                <h2>공식 원문에서 확인한 내용</h2>
                <span className={styles.status}>원문 발췌 · 검수 중</span>
            </div>
            <p className={styles.description}>
                개인별 준비 안내는 검수 중입니다. 일부 구간이니 펼쳐서 예외와
                출처를 읽고, 전체 내용은 공식 원문에서 확인해 주세요.
            </p>
            {!area && !!information?.data?.pendingAreas.length && (
                <p className={styles.pending}>
                    {information.data.pendingAreas
                        .map((value) => areaLabels[value])
                        .join('·')}
                    {' 원문은 확인 중입니다.'}
                </p>
            )}
            <div className={styles.sections}>
                {sections.map((section, index) => (
                    <NoticeDetailScrollDetails
                        key={section.id}
                        className={styles.section}
                    >
                        <summary>
                            <span className={styles.sectionHeading}>
                                <span className={styles.area}>
                                    {areaLabels[section.area]}
                                </span>
                                <strong>{readingLabel(section.title)}</strong>
                                <Icon name="down" />
                            </span>
                            {index === 0 && (
                                <span className={styles.preview}>
                                    {section.text.length > 160
                                        ? section.text.slice(0, 160) + '…'
                                        : section.text}
                                </span>
                            )}
                        </summary>
                        <div className={styles.body}>
                            {section.title.includes(
                                '[OCR · 원문 대조 필요]',
                            ) && (
                                <p className={styles.description}>
                                    {section.area === 'REFERENCE'
                                        ? '도면에서 읽은 글자입니다. 치수·색상·호수의 위치는 아래 공식 도면을 함께 확인해 주세요.'
                                        : '이미지에서 읽은 글자입니다. 숫자와 표의 배치는 아래 공식 원문을 함께 확인해 주세요.'}
                                </p>
                            )}
                            <p className={styles.text}>{section.text}</p>
                            <div className={styles.source}>
                                <OfficialLink
                                    url={section.evidence.url}
                                    className=""
                                >
                                    {readingLabel(section.evidence.label)}
                                </OfficialLink>
                                <span>
                                    자료 확인 · {section.evidence.checkedAt}
                                </span>
                            </div>
                        </div>
                    </NoticeDetailScrollDetails>
                ))}
            </div>
        </section>
    );
}
