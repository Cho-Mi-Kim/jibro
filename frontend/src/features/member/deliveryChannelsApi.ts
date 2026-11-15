import { call } from '../../shared/api/transport';

export interface DeliveryChannelSettings {
    emailEnabled: boolean;
    emailVerified: boolean;
    mailMode: 'local' | 'smtp' | 'unavailable';
    publicKey: string | null;
}

const path = '/member/channels';

export const deliveryChannelsApi = {
    settings: () => call<DeliveryChannelSettings>(path),
    email: (enabled: boolean) =>
        call<void>(path + '/email', 'PUT', { enabled }),
    ownsDevice: (endpoint: string) =>
        call<boolean>(path + '/push/status', 'POST', { endpoint }),
    subscribe: (endpoint: string, expectedUserId: string) =>
        call<void>(path + '/push', 'PUT', { endpoint, expectedUserId }),
    unsubscribe: (endpoint: string) =>
        call<void>(path + '/push/remove', 'POST', { endpoint }),
};
