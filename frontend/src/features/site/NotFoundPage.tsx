import { Link } from 'react-router';
import styles from './Site.module.css';

export function NotFoundPage() {
    return (
        <section className={styles.notFound}>
            <p className={styles.code} aria-hidden="true">
                404
            </p>
            <h1>페이지를 찾을 수 없어요</h1>
            <p>
                주소가 바뀌었거나 더 이상 제공하지 않는 페이지예요.
                <br />
                홈이나 공고 목록에서 다시 찾아보세요.
            </p>
            <div className={styles.actions}>
                <Link to="/">홈으로 이동</Link>
                <Link to="/notices">공고 찾기</Link>
            </div>
        </section>
    );
}
