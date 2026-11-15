package org.jibro.auth;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Real SMTP protocol on loopback, not an external inbox or a mocked send(). */
class AccountMailDeliveryTest {
    @Test void deliversThroughConfiguredSmtpToLocalReceiver() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(10000);
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> received = executor.submit(() -> receive(listener));
                var sender = new JavaMailSenderImpl();
                sender.setHost(listener.getInetAddress().getHostAddress());
                sender.setPort(listener.getLocalPort());
                sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
                sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
                var beans = new DefaultListableBeanFactory();
                beans.registerSingleton("sender", sender);
                var mail = new AccountMail(beans.getBeanProvider(JavaMailSender.class), new MockEnvironment(),
                    "verification@example.invalid", "unused", "127.0.0.1");
                assertEquals("smtp", mail.mode());
                mail.send("receiver@example.invalid", "Jibro local SMTP verification", "Local delivery test without personal information");
                String message = received.get(10, TimeUnit.SECONDS);
                assertTrue(message.contains("Subject: Jibro local SMTP verification"));
                assertTrue(message.contains("Local delivery test without personal information"));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test void doesNotFallBackToLocalMailboxOnPublicServer() {
        var beans = new DefaultListableBeanFactory();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-sample");
        var mail = new AccountMail(beans.getBeanProvider(JavaMailSender.class), environment, "", "unused", "0.0.0.0");
        assertEquals("unavailable", mail.mode());
    }

    private static String receive(ServerSocket server) throws IOException {
        try (Socket socket = server.accept();
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(8000);
            writer.print("220 localhost SMTP verification\r\n"); writer.flush();
            var message = new StringBuilder();
            boolean data = false;
            for (String line; (line = reader.readLine()) != null;) {
                if (data && !line.equals(".")) { message.append(line).append('\n'); continue; }
                if (data) { data = false; writer.print("250 accepted\r\n"); }
                else if (line.startsWith("DATA")) { data = true; writer.print("354 send data\r\n"); }
                else if (line.startsWith("QUIT")) { writer.print("221 goodbye\r\n"); writer.flush(); break; }
                else writer.print("250 OK\r\n");
                writer.flush();
            }
            return message.toString();
        }
    }
}
