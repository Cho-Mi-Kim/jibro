import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLayoutEffect, useRef } from 'react';
import { useSession } from '../auth/useSession';
import { demoMode } from '../../shared/api/transport';
import { deliveryChannelsApi } from './deliveryChannelsApi';
import {
    currentBrowserSubscription,
    subscribeBrowser,
    supportsBrowserPush,
} from './browserSubscription';

export function useDeliveryChannels() {
    const { user } = useSession();
    const client = useQueryClient();
    const enabled = !!user && !user.trial && !demoMode;
    const ownerId = user?.id;
    const currentOwner = useRef<string | null>(null);
    useLayoutEffect(() => {
        currentOwner.current = enabled ? (ownerId ?? null) : null;
        return () => {
            currentOwner.current = null;
        };
    }, [enabled, ownerId]);
    const key = ['channels', user?.id];
    const supported = supportsBrowserPush();
    const channels = useQuery({
        queryKey: key,
        queryFn: deliveryChannelsApi.settings,
        enabled,
    });
    const device = useQuery({
        queryKey: [...key, 'device'],
        enabled: enabled && supported,
        queryFn: async () => {
            const subscription = await currentBrowserSubscription();
            return subscription &&
                (await deliveryChannelsApi.ownsDevice(subscription.endpoint))
                ? subscription
                : null;
        },
    });
    const change = useMutation({
        onMutate: () => ({ owner: user?.id }),
        mutationFn: async (action: 'email' | 'subscribe' | 'remove') => {
            if (!enabled || !user) throw new Error('로그인이 필요합니다.');
            const owner = user.id;
            const assertCurrent = () => {
                if (currentOwner.current !== owner)
                    throw new Error(
                        '로그인 상태가 바뀌었습니다. 현재 계정에서 다시 시도해 주세요.',
                    );
            };
            assertCurrent();
            if (action === 'email') {
                await deliveryChannelsApi.email(!channels.data?.emailEnabled);
                return;
            }
            if (action === 'remove') {
                if (!device.data) return;
                await deliveryChannelsApi.unsubscribe(device.data.endpoint);
                assertCurrent();
                await device.data.unsubscribe();
                return;
            }
            if (!channels.data?.publicKey)
                throw new Error('브라우저 푸시를 사용할 수 없습니다.');
            const { subscription, replacedEndpoint, created } =
                await subscribeBrowser(channels.data.publicKey, assertCurrent);
            let registered = false;
            try {
                assertCurrent();
                if (replacedEndpoint) {
                    await deliveryChannelsApi.unsubscribe(replacedEndpoint);
                    assertCurrent();
                }
                await deliveryChannelsApi.subscribe(
                    subscription.endpoint,
                    owner,
                );
                registered = true;
                assertCurrent();
            } catch (error) {
                // Never tear down an existing subscription or one already claimed by the signed-in owner.
                if (created && !registered) {
                    const claimed = await deliveryChannelsApi
                        .ownsDevice(subscription.endpoint)
                        .catch(() => true);
                    if (!claimed)
                        await subscription.unsubscribe().catch(() => false);
                }
                throw error;
            }
        },
        onSettled: (_data, _error, _action, context) => {
            if (context?.owner)
                return client.invalidateQueries({
                    queryKey: ['channels', context.owner],
                });
        },
    });
    return { channels, device, change, supported, enabled };
}
