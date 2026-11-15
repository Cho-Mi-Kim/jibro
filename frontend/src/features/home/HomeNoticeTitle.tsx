import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { noticeListTitle } from '../housing/noticeDisplay';
import { titleTextDiff } from './titleTextDiff';
import styles from './HomeNoticeTitle.module.css';

function ChangingText({ text, visible }: { text: string; visible: boolean }) {
    const content = useRef<HTMLSpanElement>(null);
    const [width, setWidth] = useState<number>();
    useLayoutEffect(() => {
        const element = content.current;
        if (!element) return;
        const measure = () => setWidth(element.getBoundingClientRect().width);
        measure();
        const observer =
            typeof ResizeObserver === 'undefined'
                ? undefined
                : new ResizeObserver(measure);
        observer?.observe(element);
        return () => observer?.disconnect();
    }, [text]);
    return (
        <span
            className={styles.changing}
            style={{
                maxWidth: visible
                    ? width === undefined
                        ? 'none'
                        : `${width}px`
                    : '0px',
            }}
        >
            <span ref={content}>{text}</span>
        </span>
    );
}

export function HomeNoticeTitle({
    title,
    expanded = false,
}: {
    title: string;
    expanded?: boolean;
}) {
    const shortened = noticeListTitle(title);
    const parts = useMemo(
        () => titleTextDiff(shortened, title),
        [shortened, title],
    );
    return (
        <h2
            className={styles.heading}
            aria-label={expanded ? title : shortened}
        >
            <span aria-hidden="true">
                {parts.map((part, index) =>
                    part.kind === 'shared'
                        ? part.text
                        : (part.text.match(/\S{1,12}\s*|\s+/gu) ?? []).map(
                              (chunk, chunkIndex) => (
                                  <ChangingText
                                      key={`${index}-${chunkIndex}`}
                                      text={chunk}
                                      visible={
                                          part.kind ===
                                          (expanded ? 'original' : 'summary')
                                      }
                                  />
                              ),
                          ),
                )}
            </span>
        </h2>
    );
}
