import { Link } from 'react-router';
import { Icon } from '../../shared/ui/Icon';
import styles from './PreparationScopeNotice.module.css';

export function PreparationScopeNotice({ id }: { id: string }) {
    return (
        <div className={styles.notice}>
            <Icon name="user" className={styles.icon} />
            <div className={styles.copy}>
                <p>내 답변에 맞춘 안내</p>
                <small>공통 안내와 미확인 항목도 함께 보여드려요.</small>
            </div>
            <Link to={'/preparations/' + id + '/conditions/edit'}>
                답변 수정
                <Icon name="arrow" />
            </Link>
        </div>
    );
}
