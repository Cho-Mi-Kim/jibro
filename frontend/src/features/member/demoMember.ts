import { ApiError } from '../../shared/api/client';
import type { Notice, Preparation, User } from '../housing/types';
import type { Settings, Experience } from './memberApi';
const saved = new Map<string, number>(),
    archived = new Map<string, Experience>(),
    refs = new Map<string, Set<string>>(),
    read = new Set<string>();
let settings: Settings = { enabled: false, leadDays: 3, includeSaved: true },
    region = '';
export function resetDemoMember() {
    saved.clear();
    archived.clear();
    refs.clear();
    read.clear();
    region = '';
    settings = { enabled: false, leadDays: 3, includeSaved: true };
}
export const isDemoArchived = (id: string) => archived.has(id);
export function demoFacts(n: Notice) {
    return {
        title: n.title,
        agency: n.agency,
        region: n.region,
        type: n.type,
        summary: n.summary,
        startDate: n.startDate,
        endDate: n.endDate,
        guidance: n.guideState,
        source: n.evidence.url,
    };
}
export function demoMember(
    parts: string[],
    method: string,
    input: Record<string, unknown>,
    user: User,
    notices: Notice[],
    preps: Preparation[],
) {
    const [, resource, id, , source] = parts;
    if (resource === 'saved') {
        if (method === 'GET') return notices.filter((n) => saved.has(n.id));
        if (method === 'DELETE') {
            saved.delete(id);
            return;
        }
        const n = notices.find((n) => n.id === id);
        if (!n) throw new ApiError(404);
        saved.set(id, n.version);
        return;
    }
    if (resource === 'profile') {
        if (method === 'PUT') {
            user.name = String(input.name);
            region = String(input.region);
        }
        return {
            ...user,
            region,
            verified: false,
            passwordLogin: false,
            mailMode: 'unavailable',
        };
    }
    if (resource === 'alert-settings') {
        settings = {
            enabled: !!input.enabled,
            leadDays: Number(input.leadDays),
            includeSaved: !!input.includeSaved,
        };
        return;
    }
    if (resource === 'alerts') {
        if (method === 'PUT') {
            for (const key of input.keys as string[]) read.add(key);
            return;
        }
        const ids = new Set(
            preps.filter((p) => !archived.has(p.id)).map((p) => p.noticeId),
        );
        if (settings.includeSaved) for (const id of saved.keys()) ids.add(id);
        const today = new Intl.DateTimeFormat('en-CA', {
            timeZone: 'Asia/Seoul',
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
        }).format(new Date());
        const items = settings.enabled
            ? notices
                  .filter(
                      (n) =>
                          ids.has(n.id) &&
                          n.endDate &&
                          n.endDate >= today &&
                          (Date.parse(n.endDate) - Date.parse(today)) /
                              86400000 <=
                              settings.leadDays,
                  )
                  .map((n) => ({
                      key: n.id + ':' + n.endDate,
                      noticeId: n.id,
                      title: n.title,
                      label: '신청 접수 마감',
                      date: n.endDate,
                      read: read.has(n.id + ':' + n.endDate),
                  }))
            : [];
        return { settings, items };
    }
    if (resource === 'archive') {
        if (method === 'GET') return [...archived.values()];
        const p = preps.find((p) => p.id === id);
        if (!p) throw new ApiError(404);
        if (method === 'DELETE') {
            if (archived.delete(id)) {
                p.revision++;
                for (const sources of refs.values()) sources.delete(id);
            }
            return;
        }
        if (archived.has(id) || p.revision !== input.revision)
            throw new ApiError(409);
        p.revision++;
        archived.set(id, {
            id,
            title: p.title,
            version: p.version,
            progress: p.progress,
            note: String(input.note),
            archivedAt: new Date().toISOString(),
        });
        return;
    }
    if (resource === 'preparations' && parts[3] === 'references') {
        if (!preps.some((p) => p.id === id) || archived.has(id))
            throw new ApiError(404);
        if (method === 'PUT') {
            if (!archived.has(source)) throw new ApiError(404);
            const links = refs.get(id) ?? new Set<string>();
            links.add(source);
            refs.set(id, links);
            return;
        }
        return [...archived.values()].filter((x) => refs.get(id)?.has(x.id));
    }
    throw new ApiError(404, '이 시연 계정에서 지원하지 않는 동작입니다.');
}
