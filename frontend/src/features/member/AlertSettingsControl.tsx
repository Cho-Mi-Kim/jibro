import { useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../../app/useSession';
import { Icon } from '../../shared/ui/Icon';
import { Button, State } from '../../shared/ui/ui';
import { DeliveryChannels } from './DeliveryChannels';
import { memberApi, type Settings } from './memberApi';
import s from './Controls.module.css';

export function AlertSettingsControl({
    children,
    className,
}: { children?: ReactNode; className?: string } = {}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const [open, setOpen] = useState(false);
    return (
        <>
            <button
                type="button"
                aria-label="알림 설정"
                aria-haspopup="dialog"
                className={className ?? s.settingsTrigger}
                onClick={() => {
                    setOpen(true);
                    dialog.current?.showModal();
                }}
            >
                {children ?? <Icon name="settings" />}
            </button>
            {createPortal(
                <dialog
                    ref={dialog}
                    aria-labelledby="alert-settings-title"
                    className={s.settingsDialog}
                    onClose={() => setOpen(false)}
                >
                    <header>
                        <h2 id="alert-settings-title">알림 설정</h2>
                        <button
                            type="button"
                            aria-label="닫기"
                            onClick={() => dialog.current?.close()}
                        >
                            ×
                        </button>
                    </header>
                    {open && <SettingsContent />}
                </dialog>,
                document.body,
            )}
        </>
    );
}

function SettingsContent() {
    const { user } = useSession();
    const client = useQueryClient();
    const queryKey = ['alerts', user?.id];
    const inbox = useQuery({ queryKey, queryFn: memberApi.inbox });
    const save = useMutation({
        mutationFn: memberApi.configure,
        onSuccess: () => client.invalidateQueries({ queryKey }),
    });
    return (
        <>
            <State
                loading={inbox.isPending}
                error={inbox.error}
                retry={() => void inbox.refetch()}
            />
            {inbox.data && (
                <>
                    <Preferences
                        settings={inbox.data.settings}
                        pending={save.isPending}
                        save={(value) => save.mutate(value)}
                    />
                    {save.error && <p role="alert">{save.error.message}</p>}
                    {save.isSuccess && (
                        <p role="status">알림 설정을 저장했습니다.</p>
                    )}
                    {!user?.trial && <DeliveryChannels />}
                </>
            )}
        </>
    );
}
function Preferences({
    settings,
    save,
    pending,
}: {
    settings: Settings;
    save: (v: Settings) => void;
    pending: boolean;
}) {
    const [value, setValue] = useState(settings);
    return (
        <form
            className={s.alertPreferences}
            onSubmit={(e) => {
                e.preventDefault();
                save(value);
            }}
        >
            <label className={s.choice}>
                <input
                    type="checkbox"
                    checked={value.enabled}
                    onChange={(e) =>
                        setValue({ ...value, enabled: e.target.checked })
                    }
                />
                관심 공고의 변경 소식을 받습니다
            </label>
            <label className={s.field}>
                일정 사전 알림
                <select
                    value={value.leadDays}
                    onChange={(e) =>
                        setValue({ ...value, leadDays: Number(e.target.value) })
                    }
                >
                    {[1, 3, 7].map((n) => (
                        <option key={n} value={n}>
                            {n}일 전부터
                        </option>
                    ))}
                </select>
            </label>
            <Button disabled={pending}>
                {pending ? '저장 중…' : '알림 설정 저장'}
            </Button>
        </form>
    );
}
