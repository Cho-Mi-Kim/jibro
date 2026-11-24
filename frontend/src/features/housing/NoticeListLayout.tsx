import { useState } from 'react';
import { Outlet } from 'react-router';
import { NoticeVisits, type Visits } from './NoticeListVisit';

export function NoticeListLayout() {
    const [visits] = useState<Visits>(() => ({
        lists: new Map(),
        details: new Map(),
    }));
    return (
        <NoticeVisits.Provider value={visits}>
            <Outlet />
        </NoticeVisits.Provider>
    );
}
