import { Link, Route, Routes } from 'react-router';
import { SessionProvider } from './session';
import { Protected, Shell } from './Shell';
import { HomePage } from '../pages/HomePage';
import { GuidePage } from '../pages/GuidePage';
import { JourneyPage } from '../pages/JourneyPage';
import { StartPreparationPage } from '../pages/StartPreparationPage';
import { NoticesPage } from '../pages/NoticePages';
import { NoticeDetailPage } from '../pages/NoticeDetailPage';
import {
    PreparationListPage,
    PreparationPage,
} from '../pages/PreparationPages';
import { ConditionsPage } from '../pages/ConditionsPage';
import { DocumentsPage } from '../pages/DocumentsPage';
import { PreparationTabLayout } from '../features/preparation/PreparationTabLayout';
import { SchedulePage } from '../pages/SchedulePage';
import { CallbackPage, LoginPage } from '../pages/AccountPages';
import { MyPage } from '../pages/MyPage';
import { AdminPage } from '../pages/AdminPage';
import { DevelopmentPage } from '../pages/DevelopmentPage';
import { pagesDemo } from '../shared/api/environment';
import { SavedPage } from '../features/member/SavedNotices';
import { ComparePage, VersionsPage } from '../pages/ComparePage';
import { ArchivePage } from '../pages/ArchivePage';
import { AlertsPage } from '../pages/AlertsPage';
import { AccountSettingsPage } from '../pages/AccountSettingsPage';
import { RecoveryPage, ConfirmAccountPage } from '../pages/RecoveryPages';
export function App() {
    return (
        <SessionProvider>
            <Shell>
                <Routes>
                    <Route path="/compare" element={<ComparePage />} />
                    <Route
                        path="/notices/:noticeId/versions"
                        element={<VersionsPage />}
                    />
                    <Route
                        path="/saved"
                        element={
                            <Protected>
                                <SavedPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/alerts"
                        element={
                            <Protected>
                                <AlertsPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/archive"
                        element={
                            <Protected>
                                <ArchivePage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/settings"
                        element={
                            <Protected>
                                <AccountSettingsPage />
                            </Protected>
                        }
                    />
                    <Route path="/recovery" element={<RecoveryPage />} />
                    <Route
                        path="/account/confirm"
                        element={<ConfirmAccountPage />}
                    />
                    <Route path="/" element={<HomePage />} />
                    <Route path="/notices" element={<NoticesPage />} />
                    <Route
                        path="/notices/:noticeId"
                        element={<NoticeDetailPage />}
                    />
                    <Route
                        path="/notices/:noticeId/start"
                        element={
                            <Protected>
                                <StartPreparationPage />
                            </Protected>
                        }
                    />
                    <Route
                        element={
                            <Protected>
                                <PreparationTabLayout />
                            </Protected>
                        }
                    >
                        <Route
                            path="/preparations"
                            element={<PreparationListPage />}
                        />
                        <Route
                            path="/preparations/:preparationId"
                            element={<PreparationPage />}
                        />
                        <Route
                            path="/preparations/:preparationId/conditions"
                            element={<ConditionsPage mode="tab" />}
                        />
                        <Route
                            path="/preparations/:preparationId/documents"
                            element={<DocumentsPage />}
                        />
                        <Route
                            path="/preparations/:preparationId/schedule"
                            element={<SchedulePage />}
                        />
                    </Route>
                    <Route
                        path="/preparations/:preparationId/journey"
                        element={
                            <Protected>
                                <JourneyPage />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId/conditions/result"
                        element={
                            <Protected>
                                <ConditionsPage mode="result" />
                            </Protected>
                        }
                    />
                    <Route
                        path="/preparations/:preparationId/conditions/edit"
                        element={
                            <Protected>
                                <ConditionsPage mode="questions" />
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
                    {!pagesDemo && (
                        <Route
                            path="/development"
                            element={<DevelopmentPage />}
                        />
                    )}
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
