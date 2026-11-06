import { useEffect } from 'react';
const checkDrafts = 'jibro:check-unsaved-changes';
const discardPrompt =
    '저장하지 않은 변경이 있습니다. 저장하지 않고 이동할까요?';
export function allowRecordChange() {
    return (
        window.dispatchEvent(new Event(checkDrafts, { cancelable: true })) ||
        window.confirm(discardPrompt)
    );
}

export function useUnsavedChanges(dirty: boolean) {
    useEffect(() => {
        if (!dirty) return;
        const unload = (event: BeforeUnloadEvent) => {
            event.preventDefault();
            event.returnValue = '';
        };
        const navigate = (event: MouseEvent) => {
            const link = (event.target as Element).closest('a[href]');
            if (!link || link.getAttribute('target') === '_blank') return;
            if (!window.confirm(discardPrompt)) {
                event.preventDefault();
                event.stopPropagation();
            }
        };
        const hasDraft = (event: Event) => event.preventDefault();
        window.addEventListener(checkDrafts, hasDraft);
        window.addEventListener('beforeunload', unload);
        document.addEventListener('click', navigate, true);
        return () => {
            window.removeEventListener(checkDrafts, hasDraft);
            window.removeEventListener('beforeunload', unload);
            document.removeEventListener('click', navigate, true);
        };
    }, [dirty]);
}
