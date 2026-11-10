package org.jibro.auth;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class AccountMail {
    private final ObjectProvider<JavaMailSender> smtp;
    private final String sender;
    private final boolean localPickup;
    private final Path folder;

    public AccountMail(ObjectProvider<JavaMailSender> smtp, Environment env, @Value("${JIBRO_MAIL_FROM:}") String sender,
                       @Value("${JIBRO_MAIL_PICKUP:../.tools/account-mail}") String folder, @Value("${server.address:127.0.0.1}") String address) {
        this.smtp = smtp;
        this.sender = sender;
        this.folder = Path.of(folder);
        this.localPickup = env.acceptsProfiles(Profiles.of("local-sample")) && ("127.0.0.1".equals(address) || "::1".equals(address));
    }

    public String mode() {
        return smtp.getIfAvailable() != null && !sender.isBlank() ? "smtp" : localPickup ? "local" : "unavailable";
    }

    public void send(String to, String subject, String body) {
        if (to.contains("\r") || to.contains("\n")) throw new IllegalArgumentException();
        switch (mode()) {
            case "smtp" -> {
                var m = new SimpleMailMessage();
                m.setFrom(sender);
                m.setTo(to);
                m.setSubject(subject);
                m.setText(body);
                smtp.getObject().send(m);
            }
            case "local" -> {
                try {
                    Files.createDirectories(folder);
                    Files.writeString(folder.resolve(UUID.randomUUID() + ".eml"), "To: " + to + "\r\nSubject: " + subject + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (IOException ex) {
                    throw new IllegalStateException("Local mail pickup failed", ex);
                }
            }
            default -> throw new ApiException(503, "MAIL_UNAVAILABLE", "메일 발송 설정을 확인해 주세요.");
        }
    }
}
