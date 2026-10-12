package de.metis.mail.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MboxSplitterTest {

    private final MboxSplitter splitter = new MboxSplitter();

    private static final String MBOX = String.join("\n",
            ">From alice@example.com Mon Jan  1 00:00:00 2024",
            "From: Alice",
            "To: Bob",
            "Subject: Hello",
            "",
            "Body here",
            "",
            ">From bob@example.com Tue Jan  2 00:00:00 2024",
            "From: Bob",
            "To: Alice",
            "Subject: Reply",
            "",
            "From here too",
            "");

    @Test
    void splitsStringIntoTwoMessages() {
        List<String> msgs = splitter.split(MBOX);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).startsWith("From alice@example.com"), "envelope kept: " + msgs.get(0));
        assertTrue(msgs.get(0).contains("From: Alice"));
        assertTrue(msgs.get(0).contains("Body here"));
        assertTrue(msgs.get(1).startsWith("From bob@example.com"));
        assertTrue(msgs.get(1).contains("From: Bob"));
        assertTrue(msgs.get(1).contains("From here too"), "body starting with 'From' must stay in message");
    }

    @Test
    void messagesDoNotLeakAcrossDelimiter() {
        List<String> msgs = splitter.split(MBOX);
        assertFalse(msgs.get(0).contains("From: Bob"));
        assertFalse(msgs.get(1).contains("From: Alice"));
    }

    @Test
    void splitsFromFile(@TempDir Path dir) throws IOException {
        Path mbox = dir.resolve("test.mbox");
        Files.writeString(mbox, MBOX, StandardCharsets.UTF_8);
        List<String> msgs = splitter.split(mbox);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(1).contains("Subject: Reply"));
    }

    @Test
    void emptyInputGivesEmptyList() {
        assertTrue(splitter.split("").isEmpty());
    }
}
