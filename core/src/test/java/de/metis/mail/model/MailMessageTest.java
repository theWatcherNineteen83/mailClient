package de.metis.mail.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MailMessageTest {

    @Test
    void buildsMessageWithAllFields() {
        Instant date = Instant.parse("2026-10-12T10:00:00Z");
        MailMessage msg = MailMessage.builder()
                .from("Alice", "alice@example.com")
                .to("Bob", "bob@example.com")
                .cc("Carol", "carol@example.com")
                .subject("Hello")
                .sentDate(date)
                .addHeader("Message-ID", "<1@example.com>")
                .plainTextBody("plain body")
                .htmlBody("<p>html body</p>")
                .addAttachment("f.txt", "text/plain", 5, "hello".getBytes(StandardCharsets.UTF_8))
                .setUnseen(false)
                .setFlagged(true)
                .build();

        assertEquals(1, msg.getFrom().size());
        assertEquals("alice@example.com", msg.getFrom().get(0).getEmail());
        assertEquals("Alice", msg.getFrom().get(0).getName());
        assertEquals("bob@example.com", msg.getTo().get(0).getEmail());
        assertEquals("carol@example.com", msg.getCc().get(0).getEmail());
        assertEquals("Hello", msg.getSubject());
        assertEquals(date, msg.getSentDate());
        assertEquals("<1@example.com>", msg.getHeaders().get("Message-ID").get(0));
        assertEquals("plain body", msg.getPlainTextBody());
        assertEquals("<p>html body</p>", msg.getHtmlBody());
        assertFalse(msg.isUnseen());
        assertTrue(msg.isFlagged());

        MailMessage.Attachment att = msg.getAttachments().get(0);
        assertEquals("f.txt", att.getFileName());
        assertEquals("text/plain", att.getMimeType());
        assertEquals(5, att.getSize());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), att.getData());
    }

    @Test
    void requiresSubject() {
        assertThrows(NullPointerException.class,
                () -> MailMessage.builder().from("A", "a@example.com").build());
    }

    @Test
    void defaultsSentDateWhenMissing() {
        Instant before = Instant.now();
        MailMessage msg = MailMessage.builder().subject("x").build();
        assertNotNull(msg.getSentDate());
        assertFalse(msg.getSentDate().isBefore(before));
    }

    @Test
    void defaultsAreUnseenNotFlagged() {
        MailMessage msg = MailMessage.builder().subject("x").build();
        assertTrue(msg.isUnseen());
        assertFalse(msg.isFlagged());
    }

    @Test
    void collectionsAreImmutable() {
        MailMessage msg = MailMessage.builder()
                .subject("x")
                .to("B", "b@example.com")
                .addHeader("H", "v")
                .build();
        assertThrows(UnsupportedOperationException.class, () -> msg.getTo().add(null));
        assertThrows(UnsupportedOperationException.class, () -> msg.getHeaders().put("k", null));
    }

    @Test
    void attachmentDataIsCopied() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        MailMessage msg = MailMessage.builder()
                .subject("x")
                .addAttachment("a.bin", null, 3, data)
                .build();
        MailMessage.Attachment att = msg.getAttachments().get(0);
        assertEquals("application/octet-stream", att.getMimeType());
        byte[] out = att.getData();
        out[0] = 'X';
        assertEquals('a', att.getData()[0], "getData() must return a copy");
    }
}
