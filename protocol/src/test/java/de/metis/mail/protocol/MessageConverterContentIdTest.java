package de.metis.mail.protocol;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.util.Base64;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageConverterContentIdTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    private MimeMessage related(String cidHeader, String fileName) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("a@example.com"));
        msg.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress("b@example.com"));
        msg.setSubject("cid-test");

        MimeMultipart mp = new MimeMultipart("related");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>Hallo</p><img src=\"cid:logo123\">", "text/html; charset=UTF-8");
        mp.addBodyPart(html);

        MimeBodyPart img = new MimeBodyPart();
        img.setDataHandler(new DataHandler(new ByteArrayDataSource(PNG, "image/png")));
        if (cidHeader != null) img.setHeader("Content-ID", cidHeader);
        if (fileName != null) img.setFileName(fileName);
        mp.addBodyPart(img);

        msg.setContent(mp);
        msg.saveChanges();
        return msg;
    }

    @Test
    void inlineImageWithoutFileNameKeepsContentId() throws Exception {
        var m = MessageConverter.toMailMessage(related("<logo123>", null));
        assertEquals(1, m.getAttachments().size());
        var att = m.getAttachments().get(0);
        assertEquals("logo123", att.getContentId());
        assertEquals("inline-logo123.png", att.getFileName());
        assertArrayEquals(PNG, att.getData());
    }

    @Test
    void attachmentWithNameButWithoutCidHasNullContentId() throws Exception {
        var m = MessageConverter.toMailMessage(related(null, "bild.png"));
        assertEquals(1, m.getAttachments().size());
        assertNull(m.getAttachments().get(0).getContentId());
        assertEquals("bild.png", m.getAttachments().get(0).getFileName());
    }

    @Test
    void partWithoutNameAndWithoutCidIsStillIgnored() throws Exception {
        var m = MessageConverter.toMailMessage(related(null, null));
        assertTrue(m.getAttachments().isEmpty());
    }
}
