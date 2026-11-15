package org.jibro.member;

import java.util.List;
import java.util.UUID;

import org.jibro.auth.AccountMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReminderDispatchTest {
    private final ReminderRepository repository = mock(ReminderRepository.class);
    private final AlertService alerts = mock(AlertService.class);
    private final AccountMail mail = mock(AccountMail.class);
    private final BrowserPush push = mock(BrowserPush.class);
    private final ReminderDelivery delivery = new ReminderDelivery(repository, alerts, mail, push, "https://housing.example.invalid");
    private final UUID user = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final String channel = deviceId.toString();
    private final String endpoint = "https://fcm.googleapis.com/fcm/send/test-only";

    @BeforeEach
    void setup() {
        when(repository.eligibleUsers()).thenReturn(List.of(user));
        when(repository.channels(user)).thenReturn(new ReminderRepository.Channels(false, false));
        when(repository.devices(user)).thenReturn(List.of(new ReminderRepository.Device(deviceId, endpoint)));
        when(repository.claimDelivery(user, "event", channel)).thenReturn(true);
        when(repository.deliveryAllowed(user, channel)).thenReturn(true);
        when(push.publicKey()).thenReturn("test-public-key");
        when(alerts.inbox(user)).thenReturn(new AlertService.Inbox(new AlertService.Settings(true, 3, true),
            List.of(new AlertService.Alert("event", UUID.randomUUID(), "공고", "변경", null, false, null))));
    }

    @Test
    void expiredDeviceIsRemovedWithoutClaimingItWasDelivered() throws Exception {
        when(push.send(endpoint)).thenReturn(410);
        delivery.dispatch();
        verify(repository).removeDevice(user, deviceId);
        verify(repository).stopRetry(user, "event", channel);
        verify(repository, never()).markDelivered(any(), any(), any());
    }

    @Test
    void revokedConsentOrTransferredDeviceStopsAClaimedDelivery() throws Exception {
        when(repository.deliveryAllowed(user, channel)).thenReturn(false);
        delivery.dispatch();
        verify(push, never()).send(anyString());
    }

    @Test
    void oneClosedAccountDoesNotPreventTheNextAccountFromReceiving() throws Exception {
        UUID closed = UUID.randomUUID();
        when(repository.eligibleUsers()).thenReturn(List.of(closed, user));
        when(repository.channels(closed)).thenThrow(new IllegalStateException("closed"));
        when(push.send(endpoint)).thenReturn(201);
        delivery.dispatch();
        verify(repository).markDelivered(user, "event", channel);
    }

    @Test
    void acceptedClaimCannotSendAgainAndTransientFailureRemainsRetryable() throws Exception {
        when(repository.claimDelivery(user, "event", channel)).thenReturn(true, false);
        when(push.send(endpoint)).thenReturn(503);
        delivery.dispatch();
        delivery.dispatch();
        verify(push, times(1)).send(endpoint);
        verify(repository, never()).stopRetry(any(), any(), any());
        verify(repository, never()).markDelivered(any(), any(), any());
    }

    @Test
    void permanentRejectionStopsRetriesWithoutDeletingAValidDevice() throws Exception {
        when(push.send(endpoint)).thenReturn(403);
        delivery.dispatch();
        verify(repository).stopRetry(user, "event", channel);
        verify(repository, never()).removeDevice(any(), any());
        assertEquals(PushDeliveryPolicy.Outcome.RETRY, PushDeliveryPolicy.outcome(429));
        assertEquals(PushDeliveryPolicy.Outcome.RETRY, PushDeliveryPolicy.outcome(408));
    }
}
