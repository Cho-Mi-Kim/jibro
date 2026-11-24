import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import { TurnstileWidget } from './TurnstileWidget';

afterEach(() => {
    cleanup();
    delete window.turnstile;
});

it('공식 위젯에 사이트 진입 용도와 한국어를 전달하고 해제 후 콜백은 실행하지 않는다', async () => {
    const renderWidget = vi.fn().mockReturnValue('widget');
    const remove = vi.fn();
    window.turnstile = { render: renderWidget, remove };
    const onToken = vi.fn();
    const onError = vi.fn();
    const view = render(
        <TurnstileWidget
            siteKey="public-key"
            onToken={onToken}
            onError={onError}
        />,
    );
    await waitFor(() => expect(renderWidget).toHaveBeenCalledOnce());
    const options = renderWidget.mock.calls[0][1];
    expect(options).toMatchObject({
        sitekey: 'public-key',
        action: 'site_entry',
        language: 'ko',
        size: 'flexible',
    });
    options.callback('token');
    expect(onToken).toHaveBeenCalledWith('token');
    options['expired-callback']();
    expect(onError).toHaveBeenCalledOnce();
    view.unmount();
    expect(remove).toHaveBeenCalledWith('widget');
    options.callback('stale-token');
    expect(onToken).toHaveBeenCalledOnce();
});
