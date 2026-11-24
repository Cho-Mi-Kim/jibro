import { OfficialLink } from '../../shared/ui/OfficialLink';
import type { IssuanceLink } from './onlineIssuance';
import styles from './DocumentIssuanceLinks.module.css';

export function DocumentIssuanceLinks({ links }: { links: IssuanceLink[] }) {
    return links.map((link) => (
        <DocumentIssuanceLink key={link.url} {...link} />
    ));
}

export function DocumentIssuanceLink({
    url,
    label = '공식 서식 열기',
    note,
}: {
    url: string | null;
    label?: string;
    note?: string;
}) {
    return (
        <div className={styles.link}>
            <OfficialLink url={url} className="">
                {label}
            </OfficialLink>
            {note && <small>{note}</small>}
        </div>
    );
}
