import { analyticsSettingsEvent } from '../analytics/analytics';
import { sitePageHref } from './siteLinks';
import styles from './Site.module.css';

export function SiteFooter() {
    return (
        <footer className={styles.footer}>
            <nav aria-label="서비스 안내">
                <a href={sitePageHref('contact')}>문의 안내</a>
                <a href={sitePageHref('privacy')}>개인정보처리방침</a>
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
