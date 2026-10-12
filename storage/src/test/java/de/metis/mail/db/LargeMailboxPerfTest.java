package de.metis.mail.db;

import de.metis.mail.model.MailMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3-004: Large mailbox performance test — 100K messages against real SQLite.
 * Verifies batch-insert throughput, paged query latency and search on a large DB.
 */
@Tag("perf")
class LargeMailboxPerfTest {

    private static final int TOTAL = 100_000;
    private static final int BATCH = 1_000;
    // Generous bounds: CI machines vary; these catch algorithmic regressions,
    // not hardware differences.
    private static final long INSERT_BUDGET_MS = 120_000;
    private static final long PAGE_QUERY_BUDGET_MS = 5_000;

    @TempDir
    Path dir;

    @Test
    void hundredThousandMessagesInsertAndQueryFast() throws SQLException {
        try (MailStore store = new MailStore(dir.resolve("perf.db"), null)) {
            long accountId = store.createAccount("perf", "imap", "imap.example.com", 143,
                    "smtp.example.com", 587, "perf@example.com");
            long folderId = store.getOrCreateFolder(accountId, "INBOX");

            Instant base = Instant.parse("2024-01-01T00:00:00Z");
            long startInsert = System.currentTimeMillis();
            List<SyncItem> batch = new ArrayList<>(BATCH);
            for (int i = 0; i < TOTAL; i++) {
                MailMessage m = MailMessage.builder()
                        .from("Sender " + (i % 500), "sender" + (i % 500) + "@example.com")
                        .to("Perf", "perf@example.com")
                        .subject("Message subject number " + i)
                        .plainTextBody("Body of message " + i + " with some text to store.")
                        .sentDate(base.plusSeconds(i))
                        .addHeader("Message-ID", "<perf-" + i + "@example.com>")
                        .build();
                batch.add(new SyncItem(m, i % 101));
                if (batch.size() == BATCH) {
                    store.upsertMessages(folderId, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) store.upsertMessages(folderId, batch);
            long insertMs = System.currentTimeMillis() - startInsert;

            assertEquals(TOTAL, store.countMessages(folderId));
            System.out.printf("[perf] insert %d messages: %d ms (%.0f msg/s)%n",
                    TOTAL, insertMs, TOTAL * 1000.0 / insertMs);
            assertTrue(insertMs < INSERT_BUDGET_MS,
                    "insert too slow: " + insertMs + " ms (budget " + INSERT_BUDGET_MS + ")");

            // Paged query deep into the result set
            long startPage = System.currentTimeMillis();
            List<StoredMessage> page = store.listMessages(folderId, 50, TOTAL - 50);
            long pageMs = System.currentTimeMillis() - startPage;
            assertEquals(50, page.size());
            System.out.printf("[perf] deep page query (offset %d): %d ms%n", TOTAL - 50, pageMs);
            assertTrue(pageMs < PAGE_QUERY_BUDGET_MS,
                    "page query too slow: " + pageMs + " ms");

            // Search on large DB
            long startSearch = System.currentTimeMillis();
            List<StoredMessage> found = store.search(folderId, "subject number 99999", 10);
            long searchMs = System.currentTimeMillis() - startSearch;
            assertFalse(found.isEmpty());
            System.out.printf("[perf] LIKE search: %d ms%n", searchMs);
            assertTrue(searchMs < PAGE_QUERY_BUDGET_MS, "search too slow: " + searchMs + " ms");

            // Threading inputs over 100K rows must complete in bounded time
            long startThreadRead = System.currentTimeMillis();
            List<StoredMessage> all = store.listForThreading(folderId);
            long readMs = System.currentTimeMillis() - startThreadRead;
            assertEquals(TOTAL, all.size());
            System.out.printf("[perf] read all rows for threading: %d ms%n", readMs);
            assertTrue(readMs < INSERT_BUDGET_MS, "full read too slow: " + readMs + " ms");
        }
    }
}
