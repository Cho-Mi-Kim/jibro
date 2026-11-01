import { Link, Route, Routes } from 'react-router';
import { SessionProvider } from './session';
import { Protected, Shell } from './Shell';
import { HomePage, GuidePage } from '../pages/HomeGuidePages';
import { NoticesPage, NoticeDetailPage } from '../pages/NoticePages';
import {
    ConditionsPage,
    DocumentsPage,
    PreparationListPage,
    PreparationPage,
    SchedulePage,
} from '../pages/PreparationPages';
import { CallbackPage, LoginPage, MyPage } from '../pages/AccountPages';
import { AdminPage } from '../pages/AdminPage';
import { DevelopmentPage } from '../pages/DevelopmentPage';
export function App() {
    return (
        <SessionProvider>
            <Shell>
                <Routes>
                    <Route path="/" element={<HomePage />} />
                    <Route path="/notices" element={<NoticesPage />} />
                    <Route
                        path="/notices/:noticeId"
                        element={<NoticeDetailPage />}
                    />
                    <Route
                        path="/preparations"
                        element={
                            <Protected>
                                <PreparationListPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId"
                        element={
                            <Protected>
                                <PreparationPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId/conditions/edit"
                        element={
                            <Protected>
                                <ConditionsPage edit />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId/conditions"
                        element={
                            <Protected>
                                <ConditionsPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId/documents"
                        element={
                            <Protected>
                                <DocumentsPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId/schedule"
                        element={
                            <Protected>
                                <SchedulePage />
                            </Protected>
                        }
                    />
                    <Route path="/guide" element={<GuidePage />} />
                    <Route path="/my" element={<MyPage />} />
                    <Route path="/login" element={<LoginPage />} />
                    <Route path="/signup" element={<LoginPage signup />} />
                    <Route path="/auth/callback" element={<CallbackPage />} />
                    <Route
                        path="/admin"
                        element={
                            <Protected admin>
                                <AdminPage />
                            </Protected>
                        }
                    />
                    <Route path="/development" element={<DevelopmentPage />} />
                    <Route
                        path="*"
                        element={
                            <>
                                <h1>페이지를 찾을 수 없어요</h1>
                                <Link to="/">홈으로 돌아가기</Link>
                            </>
                        }
                    />
                </Routes>
            </Shell>
        </SessionProvider>
    );
}
