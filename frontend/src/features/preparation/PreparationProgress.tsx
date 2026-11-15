import { BrickHouse } from '../../shared/ui/BrickHouse';
import { bindClasses } from '../../shared/styles/classes';
import { InlineExplanation } from '../../shared/ui/ui';
import preparationClasses from './PreparationUI.module.css';

const cx = bindClasses(preparationClasses);

export function House({
    progress,
    stage,
    onExplanationToggle,
}: {
    progress: number;
    stage: number;
    onExplanationToggle?: (open: boolean) => void;
}) {
    return (
        <div className={cx('house-row')}>
            <div>
                <span>전체 준비도</span>
                <strong className={cx('progress-number')}>{progress}%</strong>
                <progress max="100" value={progress} aria-label="준비도" />
            </div>
            <div className={cx('house-picture')}>
                <BrickHouse progress={progress} stage={stage} />
            </div>
            <InlineExplanation
                label="준비도 설명"
                onToggle={onExplanationToggle}
            >
                <small>
                    준비도는 기록한 준비 작업의 진행률이며,
                    <br />
                    신청 자격이나 선정 가능성을 의미하지 않습니다.
                </small>
            </InlineExplanation>
        </div>
    );
}
