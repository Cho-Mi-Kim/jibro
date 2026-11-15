import { OfficialLink } from '../../shared/ui/OfficialLink';
import type { IssuanceLink } from './onlineIssuance';
import styles from './DocumentIssuanceLinks.module.css';

export function DocumentIssuanceLinks({ links }: { links: IssuanceLink[] }) {
    return links.map((link) => (
        <div key={link.url} className={styles.link}>
            <OfficialLink url={link.url} className="">
                {link.label}
            </OfficialLink>
            {link.note && <small>{link.note}</small>}
        </div>
    ));
}
