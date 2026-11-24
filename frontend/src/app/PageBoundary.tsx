import { Component, Suspense, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { State } from '../shared/ui/ui';
import { cx } from '../shared/styles/classes';

class PageErrorBoundary extends Component<
    { children: ReactNode; locationKey: string },
    { failed: boolean }
> {
    state = { failed: false };

    static getDerivedStateFromError() {
        return { failed: true };
    }

    componentDidUpdate(previous: Readonly<{ locationKey: string }>) {
        if (
            this.state.failed &&
            previous.locationKey !== this.props.locationKey
        )
            this.setState({ failed: false });
    }

    render() {
        if (this.state.failed)
            return (
                <section className={cx('state')} role="alert">
                    <h1>화면을 불러오지 못했어요</h1>
                    <p>연결 상태를 확인한 뒤 새로고침해 주세요.</p>
                    <button
                        type="button"
                        className={cx('primary-button')}
                        onClick={() => window.location.reload()}
                    >
                        새로고침
                    </button>{' '}
                    <Link to="/">홈으로 돌아가기</Link>
                </section>
            );
        return this.props.children;
    }
}

/** Keep navigation and session state available while a page loads or fails. */
export function PageBoundary({ children }: { children: ReactNode }) {
    const location = useLocation();
    return (
        <PageErrorBoundary locationKey={location.key}>
            <Suspense fallback={<State loading />}>{children}</Suspense>
        </PageErrorBoundary>
    );
}
