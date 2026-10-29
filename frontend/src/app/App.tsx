import { Navigate, Route, Routes } from 'react-router';
import { DevelopmentPage } from '../pages/DevelopmentPage';

export function App() {
    return (
        <Routes>
            <Route path="/" element={<DevelopmentPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
    );
}
