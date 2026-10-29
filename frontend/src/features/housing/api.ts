import { call, clearToken, setToken } from '../../shared/api/transport';
import type {
    Answer,
    Collection,
    DocumentState,
    Guide,
    Notice,
    Page,
    Preparation,
    Review,
    User,
} from './types';
export const api = {
    meta: () => call<{ sampleData: boolean }>('/meta'),
    providers: () => call<string[]>('/auth/providers'),
    notices: (query: string) => call<Page<Notice>>('/notices?' + query),
    filters: () =>
        call<{ regions: string[]; types: string[] }>('/notices/filters'),
    notice: (id: string) => call<Notice>('/notices/' + encodeURIComponent(id)),
    me: () => call<User>('/users/me'),
    login: async (email: string, password: string) => {
        const result = await call<{ accessToken: string; user: User }>(
            '/auth/login',
            'POST',
            { email, password },
        );
        setToken(result.accessToken);
        return result.user;
    },
    signup: (name: string, email: string, password: string) =>
        call<void>('/auth/signup', 'POST', { name, email, password }),
    logout: async () => {
        await call('/auth/logout', 'POST');
        clearToken();
    },
    preparations: () => call<Preparation[]>('/preparations'),
    preparation: (id: string) =>
        call<Preparation>('/preparations/' + encodeURIComponent(id)),
    start: (noticeId: string, version: number) =>
        call<Preparation>('/preparations', 'POST', { noticeId, version }),
    answers: (id: string, revision: number, answers: Record<string, Answer>) =>
        call<Preparation>('/preparations/' + id + '/answers', 'PUT', {
            revision,
            answers,
        }),
    document: (
        id: string,
        documentId: string,
        revision: number,
        state: DocumentState,
        note: string,
    ) =>
        call<Preparation>(
            '/preparations/' + id + '/documents/' + documentId,
            'PATCH',
            { revision, state, note },
        ),
    schedule: (
        id: string,
        scheduleId: string,
        revision: number,
        recorded: boolean,
    ) =>
        call<Preparation>(
            '/preparations/' + id + '/schedules/' + scheduleId,
            'PATCH',
            { revision, recorded },
        ),
    guides: () => call<Guide[]>('/guides'),
    reviews: () => call<Review[]>('/admin/reviews'),
    review: (
        id: string,
        review: Pick<Review, 'basic' | 'guide' | 'reason' | 'revision'>,
    ) => call<Review>('/admin/reviews/' + id, 'PATCH', review),
    collections: () => call<Collection[]>('/admin/collections'),
    collect: (startPage = 1) =>
        call<Collection>('/admin/collections?startPage=' + startPage, 'POST'),
    draft: (id: string) => call<unknown>('/admin/reviews/' + id + '/draft'),
    saveDraft: (id: string, revision: number, draft: unknown) =>
        call<Review>('/admin/reviews/' + id + '/draft', 'PUT', {
            revision,
            draft,
        }),
};
