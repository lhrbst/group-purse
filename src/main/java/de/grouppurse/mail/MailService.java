package de.grouppurse.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {
    private final JavaMailSender sender;
    private final String from;

    public MailService(JavaMailSender sender, @Value("${app.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public void send(String to, String subject, String text) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(text);
        sender.send(m);
    }
}
