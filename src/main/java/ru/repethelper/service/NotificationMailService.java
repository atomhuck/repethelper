package ru.repethelper.service;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NotificationMailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationMailService.class);
    private static final Pattern HTTPS_URL = Pattern.compile("https://[^\\s<>]+");

    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String replyTo;

    public NotificationMailService(JavaMailSender sender,
                                   @Value("${app.mail.enabled:false}") boolean enabled,
                                   @Value("${app.mail.from:no-reply@repethelper.ru}") String from,
                                   @Value("${app.mail.reply-to:efimok05@gmail.com}") String replyTo) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
        this.replyTo = replyTo;
    }

    public void sendVerification(String email, String code) {
        String subject = "Подтвердите email в RepetHelper";
        String body = "Здравствуйте!\n\nВаш код подтверждения RepetHelper:\n\n" + code
                + "\n\nКод действует 15 минут и может быть использован один раз."
                + "\nЕсли это были не вы, просто проигнорируйте письмо.";
        send(email, subject, body, renderCodeHtml(subject,
                "Введите этот код на странице подтверждения email.", code,
                "Код действует 15 минут и может быть использован один раз."));
    }

    public void sendPasswordReset(String email, String code) {
        String subject = "Сброс пароля RepetHelper";
        String body = "Ваш код для создания нового пароля:\n\n" + code
                + "\n\nКод действует 15 минут и может быть использован один раз."
                + "\nЕсли вы не запрашивали сброс пароля, просто проигнорируйте письмо.";
        send(email, subject, body, renderCodeHtml(subject,
                "Введите этот код на странице восстановления пароля.", code,
                "Код действует 15 минут и может быть использован один раз."));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void sendNotification(String email, String subject, String body) {
        send(email, subject, body, renderHtml(subject, body));
    }

    private void send(String email, String subject, String plainText, String htmlText) {
        if (!enabled) {
            log.info("Отправка почты отключена; письмо не отправлено");
            return;
        }
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "RepetHelper");
            helper.setReplyTo(replyTo);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(plainText, htmlText);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailSendException("Не удалось подготовить email-уведомление", ex);
        }
    }

    static String renderHtml(String subject, String body) {
        String ctaUrl = firstHttpsUrl(body);
        String content = linkifyAndPreserveLines(body);
        String cta = ctaUrl == null ? "" : """
                <div style="margin:28px 0 8px">
                  <a href="%s" style="display:inline-block;padding:13px 20px;border-radius:12px;background:#4F46E5;color:#FFFFFF;text-decoration:none;font-weight:700">Открыть RepetHelper</a>
                </div>
                """.formatted(HtmlUtils.htmlEscape(ctaUrl));
        return emailShell(subject, """
                <div style="color:#45443F;font-size:15px;line-height:1.7">%s</div>
                %s
                """.formatted(content, cta));
    }

    static String renderCodeHtml(String subject, String intro, String code, String note) {
        return emailShell(subject, """
                <p style="margin:0 0 18px;color:#45443F;font-size:15px;line-height:1.65">%s</p>
                <div style="margin:0 0 18px;padding:18px;border:1px solid #DEDBD4;border-radius:10px;background:#F1F0EC;text-align:center">
                  <span style="font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:30px;font-weight:800;letter-spacing:7px;color:#4F46E5">%s</span>
                </div>
                <p style="margin:0;color:#73716A;font-size:13px;line-height:1.55">%s</p>
                """.formatted(HtmlUtils.htmlEscape(intro), HtmlUtils.htmlEscape(code), HtmlUtils.htmlEscape(note)));
    }

    private static String emailShell(String subject, String content) {
        return """
                <!doctype html>
                <html lang="ru">
                <body style="margin:0;padding:0;background:#F6F5F2;font-family:Arial,'Segoe UI',sans-serif;color:#1D1D1B">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#F6F5F2">
                    <tr><td align="center" style="padding:28px 14px">
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px">
                        <tr><td style="padding:0 4px 16px">
                          <table role="presentation" cellspacing="0" cellpadding="0"><tr>
                            <td style="width:34px;height:34px;border-radius:8px;background:#4F46E5;color:#FFFFFF;text-align:center;font-weight:800">R</td>
                            <td style="padding-left:10px;font-size:17px;font-weight:700;color:#1D1D1B">RepetHelper</td>
                          </tr></table>
                        </td></tr>
                        <tr><td style="padding:28px;border:1px solid #DEDBD4;border-radius:14px;background:#FFFFFF">
                          <h1 style="margin:0 0 20px;font-size:24px;line-height:1.25;letter-spacing:-.4px;color:#1D1D1B">%s</h1>
                          %s
                        </td></tr>
                        <tr><td style="padding:16px 4px 0;color:#73716A;font-size:12px;line-height:1.5">
                          Это сервисное письмо RepetHelper. Не пересылайте коды подтверждения другим людям.
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(subject), content);
    }

    private static String firstHttpsUrl(String body) {
        Matcher matcher = HTTPS_URL.matcher(body == null ? "" : body);
        return matcher.find() ? trimTrailingPunctuation(matcher.group()) : null;
    }

    private static String linkifyAndPreserveLines(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        Matcher matcher = HTTPS_URL.matcher(body);
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            html.append(HtmlUtils.htmlEscape(body.substring(cursor, matcher.start())));
            String original = matcher.group();
            String url = trimTrailingPunctuation(original);
            html.append("<a href=\"")
                    .append(HtmlUtils.htmlEscape(url))
                    .append("\" style=\"color:#4F46E5;text-decoration:underline\">")
                    .append(HtmlUtils.htmlEscape(url))
                    .append("</a>");
            html.append(HtmlUtils.htmlEscape(original.substring(url.length())));
            cursor = matcher.end();
        }
        html.append(HtmlUtils.htmlEscape(body.substring(cursor)));
        return html.toString().replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }

    private static String trimTrailingPunctuation(String value) {
        String result = value;
        while (!result.isEmpty() && ".,;:!?)".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
