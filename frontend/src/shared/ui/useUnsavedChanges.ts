import { useEffect } from 'react';

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
            if (
                !window.confirm(
                    '저장하지 않은 변경이 있습니다. 저장하지 않고 이동할까요?',
                )
            ) {
                event.preventDefault();
                event.stopPropagation();
            }
        };
        window.addEventListener('beforeunload', unload);
        document.addEventListener('click', navigate, true);
        return () => {
            window.removeEventListener('beforeunload', unload);
            document.removeEventListener('click', navigate, true);
        };
    }, [dirty]);
}
