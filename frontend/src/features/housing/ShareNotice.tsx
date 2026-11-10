import { useRef, useState } from 'react';
import { Button } from '../../shared/ui/ui';
import { Icon } from '../../shared/ui/Icon';
import { safeUrl } from '../../shared/ui/helpers';
import styles from './ShareNotice.module.css';
export function ShareNotice({ url }: { url: string | null }) {
    const dialog = useRef<HTMLDialogElement>(null);
    const [message, setMessage] = useState('');
    if (!safeUrl(url) || !url) return null;
    async function copy(link: string) {
        try {
            await navigator.clipboard.writeText(link);
            setMessage('공식 공고 주소를 복사했어요.');
        } catch {
            setMessage('아래 주소를 선택해 복사하세요.');
        }
    }
    return (
        <>
            <button
                type="button"
                className={styles.trigger}
                aria-label="공식 공고 링크 공유"
                onClick={() => {
                    setMessage('');
                    dialog.current?.showModal();
                }}
            >
                <Icon name="share" />
            </button>
            <dialog
                ref={dialog}
                className={styles.dialog}
                aria-label="공식 공고 링크 공유"
            >
                <h2>공식 공고 링크 공유</h2>
                <label>
                    운영기관 원문 주소
                    <input
                        readOnly
                        value={url}
                        onFocus={(event) => event.target.select()}
                    />
                </label>
                <p role="status">{message}</p>
                <div>
                    <Button onClick={() => void copy(url)}>주소 복사</Button>
                    <Button
                        className="secondary"
                        onClick={() => dialog.current?.close()}
                    >
                        닫기
                    </Button>
                </div>
            </dialog>
        </>
    );
}
