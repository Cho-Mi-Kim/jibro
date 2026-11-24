import { lazy } from 'react';
import { PageBoundary } from './PageBoundary';
import { Route, Routes } from 'react-router';
import { NotFoundPage } from '../features/site/NotFoundPage';
import { SessionProvider } from '../features/auth/SessionProvider';
import { Protected, Shell } from './Shell';
import { NoticeListLayout } from '../features/housing/NoticeListLayout';
import { pagesDemo } from '../shared/api/environment';
import { AccessibilityProvider } from '../features/accessibility/AccessibilityProvider';
const OneTimeCheckPage = lazy(() =>
    import('../pages/OneTimeCheckPage').then((module) => ({
        default: module.OneTimeCheckPage,
    })),
);
const HomePage = lazy(() =>
    import('../pages/HomePage').then((module) => ({
        default: module.HomePage,
    })),
);
const GuidePage = lazy(() =>
    import('../pages/GuidePage').then((module) => ({
        default: module.GuidePage,
    })),
);
const JourneyPage = lazy(() =>
    import('../pages/JourneyPage').then((module) => ({
        default: module.JourneyPage,
    })),
);
const StartPreparationPage = lazy(() =>
    import('../pages/StartPreparationPage').then((module) => ({
        default: module.StartPreparationPage,
    })),
);
const NoticesPage = lazy(() =>
    import('../pages/NoticePages').then((module) => ({
        default: module.NoticesPage,
    })),
);
const NoticeDetailPage = lazy(() =>
    import('../pages/NoticeDetailPage').then((module) => ({
        default: module.NoticeDetailPage,
    })),
);
const PreparationListPage = lazy(() =>
    import('../pages/PreparationPages').then((module) => ({
        default: module.PreparationListPage,
    })),
);
const PreparationPage = lazy(() =>
    import('../pages/PreparationPages').then((module) => ({
        default: module.PreparationPage,
    })),
);
const ConditionsPage = lazy(() =>
    import('../pages/ConditionsPage').then((module) => ({
        default: module.ConditionsPage,
    })),
);
const DocumentsPage = lazy(() =>
    import('../pages/DocumentsPage').then((module) => ({
        default: module.DocumentsPage,
    })),
);
const PreparationTabLayout = lazy(() =>
    import('../features/preparation/PreparationTabLayout').then((module) => ({
        default: module.PreparationTabLayout,
    })),
);
const SchedulePage = lazy(() =>
    import('../pages/SchedulePage').then((module) => ({
        default: module.SchedulePage,
    })),
);
const FollowUpPage = lazy(() =>
    import('../pages/FollowUpPage').then((module) => ({
        default: module.FollowUpPage,
    })),
);
const CallbackPage = lazy(() =>
    import('../pages/AccountPages').then((module) => ({
        default: module.CallbackPage,
    })),
);
const LoginPage = lazy(() =>
    import('../pages/AccountPages').then((module) => ({
        default: module.LoginPage,
    })),
);
const MyPage = lazy(() =>
    import('../pages/MyPage').then((module) => ({ default: module.MyPage })),
);
const AdminPage = lazy(() =>
    import('../pages/AdminPage').then((module) => ({
        default: module.AdminPage,
    })),
);
const DevelopmentPage = lazy(() =>
    import('../pages/DevelopmentPage').then((module) => ({
        default: module.DevelopmentPage,
    })),
);
const SavedComparisonPage = lazy(() =>
    import('../features/member/SavedPage').then((module) => ({
        default: module.SavedComparisonPage,
    })),
);
const SavedPage = lazy(() =>
    import('../features/member/SavedPage').then((module) => ({
        default: module.SavedPage,
    })),
);
const ComparePage = lazy(() =>
    import('../pages/ComparePage').then((module) => ({
        default: module.ComparePage,
    })),
);
const VersionsPage = lazy(() =>
    import('../pages/ComparePage').then((module) => ({
        default: module.VersionsPage,
    })),
);
const ArchivePage = lazy(() =>
    import('../pages/ArchivePage').then((module) => ({
        default: module.ArchivePage,
    })),
);
const AlertsPage = lazy(() =>
    import('../pages/AlertsPage').then((module) => ({
        default: module.AlertsPage,
    })),
);
const AccountSettingsPage = lazy(() =>
    import('../pages/AccountSettingsPage').then((module) => ({
        default: module.AccountSettingsPage,
    })),
);
const RecoveryPage = lazy(() =>
    import('../pages/RecoveryPages').then((module) => ({
        default: module.RecoveryPage,
    })),
);
const ConfirmAccountPage = lazy(() =>
    import('../pages/RecoveryPages').then((module) => ({
        default: module.ConfirmAccountPage,
    })),
);
const AccessibilityPage = lazy(() =>
    import('../features/accessibility/AccessibilityPage').then((module) => ({
        default: module.AccessibilityPage,
    })),
);
const PreparationHistoryPage = lazy(() =>
    import('../features/preparation/history/PreparationHistoryPage').then(
        (module) => ({ default: module.PreparationHistoryPage }),
    ),
);

export function App() {
    return (
        <AccessibilityProvider>
            <SessionProvider>
                <Shell>
                    <PageBoundary>
                        <Routes>
                            <Route
                                path="/accessibility"
                                element={<AccessibilityPage />}
                            />
                            <Route
                                path="/preparations/:preparationId/history"
                                element={
                                    <Protected>
                                        <PreparationHistoryPage />
                                    </Protected>
                                }
                            />
                            <Route element={<NoticeListLayout />}>
                                <Route
                                    path="/notices/:noticeId/check"
                                    element={<OneTimeCheckPage mode="choice" />}
                                />
                                <Route
                                    path="/notices/:noticeId/check/once"
                                    element={<OneTimeCheckPage mode="once" />}
                                />
                                <Route
                                    path="/notices/:noticeId/versions"
                                    element={<VersionsPage />}
                                />
                                <Route
                                    path="/compare"
                                    element={<ComparePage />}
                                />
                                <Route
                                    path="/notices"
                                    element={<NoticesPage />}
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
                                    path="/saved/compare"
                                    element={
                                        <Protected>
                                            <SavedComparisonPage />
                                        </Protected>
                                    }
                                />
                                <Route
                                    path="/notices/:noticeId"
                                    element={<NoticeDetailPage />}
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
                                    <Route
                                        path="/preparations/:preparationId/follow-up"
                                        element={<FollowUpPage />}
                                    />
                                </Route>
                            </Route>
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
                            <Route
                                path="/recovery"
                                element={<RecoveryPage />}
                            />
                            <Route
                                path="/account/confirm"
                                element={<ConfirmAccountPage />}
                            />
                            <Route path="/" element={<HomePage />} />
                            <Route
                                path="/notices/:noticeId/start"
                                element={
                                    <Protected>
                                        <StartPreparationPage />
                                    </Protected>
                                }
                            />
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
                            <Route
                                path="/signup"
                                element={<LoginPage signup />}
                            />
                            <Route
                                path="/auth/callback"
                                element={<CallbackPage />}
                            />
                            <Route
                                path="/admin"
                                element={
                                    <Protected admin>
                                        <AdminPage />
                                    </Protected>
                                }
                            />
                            {import.meta.env.DEV && !pagesDemo && (
                                <Route
                                    path="/development"
                                    element={<DevelopmentPage />}
                                />
                            )}
                            <Route path="*" element={<NotFoundPage />} />
                        </Routes>
                    </PageBoundary>
                </Shell>
            </SessionProvider>
        </AccessibilityProvider>
    );
}
