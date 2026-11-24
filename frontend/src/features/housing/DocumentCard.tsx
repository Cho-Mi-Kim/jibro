import {
    useId,
    type ComponentType,
    type HTMLAttributes,
    type ReactNode,
} from 'react';
import { Collapse } from '../../shared/ui/Collapse';
import { Icon } from '../../shared/ui/Icon';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { DocumentIssuanceLinks } from './DocumentIssuanceLinks';
import type { documentSummary } from './documentSummary';
import { ReviewIssues } from './ReviewIssues';
import { DocumentSummaryFacts } from './DocumentSummaryFacts';
import styles from './DocumentCard.module.css';

type EditorPanel = {
    open: boolean;
    toggle: () => void;
    className: string;
    header: ReactNode;
    status: ReactNode;
};

type Display =
    | { editor: EditorPanel; groupOpen?: never; disclosure?: never }
    | { groupOpen: boolean; editor?: never; disclosure?: never }
    | {
          disclosure?: ComponentType<HTMLAttributes<HTMLDetailsElement>>;
          editor?: never;
          groupOpen?: never;
      };

export function DocumentCard({
    title,
    summary,
    heading: Heading = 'strong',
    children,
    editor,
    groupOpen,
    disclosure,
}: Display & {
    title: string;
    summary: Pick<
        ReturnType<typeof documentSummary>,
        | 'audience'
        | 'method'
        | 'methodDetails'
        | 'audienceDetails'
        | 'links'
        | 'issues'
        | 'historical'
        | 'needsReview'
    >;
    heading?: 'strong' | 'h3' | 'h4';
    children?: ReactNode;
}) {
    const panelId = useId();
    const Disclosure = disclosure ?? 'details';
    const label = (
        <Heading id={panelId + '-title'} className={styles.name}>
            {title}
        </Heading>
    );
    const body = (
        <>
            <ReviewIssues
                issues={summary.issues}
                flagged={summary.needsReview ? '서류' : undefined}
                historical={summary.historical}
            />
            <DocumentSummaryFacts summary={summary} />
            <DocumentIssuanceLinks links={summary.links} />
            {children}
        </>
    );

    // The overview opens all core documents with its single section toggle.
    if (groupOpen !== undefined)
        return (
            <>
                {label}
                <Collapse open={groupOpen}>{body}</Collapse>
            </>
        );

    const trigger = (
        <>
            <Icon name="file" />
            {label}
            {editor && (
                <span id={panelId + '-status'} className={styles.status}>
                    {editor.status}
                </span>
            )}
            <Icon name="down" />
        </>
    );
    if (editor)
        return (
            <>
                <button
                    type="button"
                    className={styles.trigger}
                    aria-controls={panelId}
                    aria-labelledby={panelId + '-title'}
                    aria-describedby={panelId + '-status'}
                    aria-expanded={editor.open}
                    onClick={editor.toggle}
                >
                    {trigger}
                </button>
                <section
                    id={panelId}
                    className={editor.className}
                    hidden={!editor.open}
                >
                    {editor.header}
                    {body}
                </section>
            </>
        );
    return (
        <Disclosure onClick={handleDisclosureCardClick}>
            <summary className={styles.trigger}>{trigger}</summary>
            {body}
        </Disclosure>
    );
}
