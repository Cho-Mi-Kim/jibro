import { analyticsSettingsEvent } from '../analytics/analytics';
import styles from './Site.module.css';

export function SiteFooter() {
    const base = import.meta.env.MODE === 'pages-demo' ? '/jibro/' : '/';
    const href = (page: string) =>
        base === '/' ? `/${page}` : `${base}${page}.html`;
    return (
        <footer className={styles.footer}>
            <nav aria-label="서비스 안내">
                <a href={href('contact')}>문의 안내</a>
                <a href={href('privacy')}>개인정보처리방침</a>
                <button
                    type="button"
                    onClick={() =>
                        window.dispatchEvent(new Event(analyticsSettingsEvent))
                    }
                >
                    통계 설정
                </button>
            </nav>
        </footer>
    );
}
