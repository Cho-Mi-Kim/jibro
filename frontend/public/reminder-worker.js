self.addEventListener('push', (event) => {
    event.waitUntil(
        self.registration.showNotification('집으로 · 공고 알림', {
            body: '알림함에서 공고의 변경 사항과 기한을 확인하세요.',
            tag: 'jibro-reminders',
        }),
    );
});
self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        self.clients.openWindow(
            new URL('alerts', self.registration.scope).href,
        ),
    );
});
