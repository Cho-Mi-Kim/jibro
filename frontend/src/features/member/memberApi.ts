import { call } from '../../shared/api/transport';
export interface SavedNotice {
    id: string;
    title: string;
    agency: string;
    region: string;
    endDate: string | null;
}
export interface Settings {
    enabled: boolean;
    leadDays: number;
    includeSaved: boolean;
}
export interface AlertItem {
    key: string;
    noticeId: string;
    title: string;
    label: string;
    date: string | null;
    read: boolean;
}
export interface Inbox {
    settings: Settings;
    items: AlertItem[];
}
export interface Experience {
    id: string;
    title: string;
    version: number;
    progress: number;
    note: string;
    archivedAt: string;
}
export interface Profile {
    name: string;
    email: string;
    region: string;
    verified: boolean;
    passwordLogin: boolean;
    mailMode: string;
}
export interface Edition {
    version: number;
    revision: number;
    facts: Record<string, string | string[] | null>;
}
const member = '/member';
export const memberApi = {
    saved: () => call<SavedNotice[]>(member + '/saved'),
    save: (id: string, saved: boolean) =>
        call<void>(
            member + '/saved/' + encodeURIComponent(id),
            saved ? 'PUT' : 'DELETE',
        ),
    inbox: () => call<Inbox>(member + '/alerts'),
    configure: (settings: Settings) =>
        call<void>(member + '/alert-settings', 'PUT', settings),
    read: async (keys: string[]) => {
        for (let offset = 0; offset < keys.length; offset += 500) {
            await call<void>(member + '/alerts/read', 'PUT', {
                keys: keys.slice(offset, offset + 500),
            });
        }
    },
    archive: () => call<Experience[]>(member + '/archive'),
    finish: (id: string, revision: number, note: string) =>
        call<void>(member + '/archive/' + id, 'PUT', { revision, note }),
    restore: (id: string) => call<void>(member + '/archive/' + id, 'DELETE'),
    references: (id: string) =>
        call<Experience[]>(member + '/preparations/' + id + '/references'),
    reuse: (target: string, source: string) =>
        call<void>(
            member + '/preparations/' + target + '/references/' + source,
            'PUT',
        ),
    editions: (id: string) =>
        call<Edition[]>('/notices/' + encodeURIComponent(id) + '/versions'),
    profile: () => call<Profile>(member + '/profile'),
    edit: (name: string, region: string) =>
        call<Profile>(member + '/profile', 'PUT', { name, region }),
    password: (current: string, password: string) =>
        call<void>(member + '/password', 'PUT', { current, password }),
    mailMode: () => call<{ mode: string }>('/auth/mail-mode'),
    requestReset: (email: string) =>
        call<void>('/auth/reset/request', 'POST', { email }),
    reset: (token: string, password: string) =>
        call<void>('/auth/reset/complete', 'POST', { token, password }),
    sendVerify: () => call<void>(member + '/verify', 'POST'),
    verify: (token: string) => call<void>('/auth/verify', 'POST', { token }),
    sendClosure: () => call<void>(member + '/closure/request', 'POST'),
    close: (token: string, confirmation: string) =>
        call<void>(member + '/closure/complete', 'POST', {
            token,
            confirmation,
        }),
};
