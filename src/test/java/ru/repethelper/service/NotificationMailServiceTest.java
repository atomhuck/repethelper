package ru.repethelper.service;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationMailServiceTest {

    @Test
    void notificationHtmlEscapesUserContentAndKeepsHttpsLinksClickable() {
        String html = NotificationMailService.renderHtml(
                "Новое <занятие>",
                "Преподаватель <script>alert('x')</script>\nОткрыть: https://repethelper.ru/lessons/42");

        assertThat(html)
                .contains("Новое &lt;занятие&gt;")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                .contains("href=\"https://repethelper.ru/lessons/42\"")
                .doesNotContain("<script>");
    }

    @Test
    void codeHtmlEscapesCodeAndUsesReadableCodeBlock() {
        String html = NotificationMailService.renderCodeHtml(
                "Подтверждение", "Введите код", "12<456", "Действует 15 минут");

        assertThat(html)
                .contains("12&lt;456")
                .contains("letter-spacing:7px")
                .doesNotContain("12<456");
    }

    @Test
    void notificationHtmlDoesNotCreateButtonForNonHttpsText() {
        String html = NotificationMailService.renderHtml("Событие", "Адрес: javascript:alert(1)");

        assertThat(html)
                .doesNotContain("<a href=")
                .contains("javascript:alert(1)");
    }

    @Test
    void notificationIsBuiltAsMultipartWithPlainTextAndHtmlAlternatives() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        NotificationMailService service = new NotificationMailService(
                sender, true, "no-reply@repethelper.ru", "support@repethelper.ru");

        service.sendNotification("student@example.test", "Новое занятие", "Откройте занятие");
        message.saveChanges();

        verify(sender).send(message);
        assertThat(message.isMimeType("multipart/*")).isTrue();
        assertThat(leafContentTypes(message))
                .anyMatch(type -> type.startsWith("text/plain"))
                .anyMatch(type -> type.startsWith("text/html"));
    }

    private static List<String> leafContentTypes(Part part) throws Exception {
        if (!part.isMimeType("multipart/*")) {
            return List.of(part.getContentType().toLowerCase(Locale.ROOT));
        }
        Multipart multipart = (Multipart) part.getContent();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < multipart.getCount(); index++) {
            result.addAll(leafContentTypes(multipart.getBodyPart(index)));
        }
        return result;
    }
}
