package de.metis.mail.threading;

import de.metis.mail.threading.MessageThreader.ThreadInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageThreaderTest {

    private final MessageThreader threader = new MessageThreader();

    private static ThreadInput in(long dbId, String msgId, String inReplyTo, String refs) {
        return new ThreadInput(dbId, msgId, inReplyTo, refs);
    }

    @Test
    void chainViaReferencesFormsOneThread() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(1, "<a@x>", null, null),
                in(2, "<b@x>", "<a@x>", "<a@x>"),
                in(3, "<c@x>", "<b@x>", "<a@x> <b@x>")));
        assertEquals(3, t.size());
        assertEquals(1L, t.get(1L));
        assertEquals(1L, t.get(2L));
        assertEquals(1L, t.get(3L));
    }

    @Test
    void replyOnlyViaInReplyTo() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(10, "<root@x>", null, null),
                in(11, "<reply@x>", "<root@x>", null)));
        assertEquals(10L, t.get(11L));
    }

    @Test
    void separateThreadsStaySeparate() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(1, "<a@x>", null, null),
                in(2, "<b@x>", null, null)));
        assertNotEquals(t.get(1L), t.get(2L));
    }

    @Test
    void emptyParentContainerIsPruned() {
        // Reply references a message we never saw -> container <missing@x> is empty.
        // The seen message must still get a thread id (its own or the reply's).
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(5, "<seen@x>", "<missing@x>", "<missing@x>")));
        assertEquals(5L, t.get(5L));
    }

    @Test
    void childArrivingBeforeParentThreadsCorrectly() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(2, "<b@x>", "<a@x>", "<a@x>"),
                in(1, "<a@x>", null, null)));
        assertEquals(1L, t.get(1L));
        assertEquals(1L, t.get(2L));
    }

    @Test
    void selfReferenceDoesNotHang() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(1, "<a@x>", "<a@x>", "<a@x>")));
        assertEquals(1L, t.get(1L));
    }

    @Test
    void referenceCycleDoesNotHang() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(1, "<a@x>", "<b@x>", "<b@x>"),
                in(2, "<b@x>", "<a@x>", "<a@x>")));
        assertEquals(2, t.size());
        assertEquals(t.get(1L), t.get(2L), "cyclic pair must land in one thread");
    }

    @Test
    void duplicateMessageIdsKeepFirst() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(1, "<dup@x>", null, null),
                in(2, "<dup@x>", null, null)));
        assertEquals(1L, t.get(1L));
        // Second occurrence with same id belongs to the same container/thread
        assertEquals(1L, t.get(2L));
    }

    @Test
    void missingMessageIdBecomesOwnThread() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(7, null, null, null),
                in(8, "", null, null),
                in(1, "<a@x>", null, null)));
        assertEquals(7L, t.get(7L));
        assertEquals(8L, t.get(8L));
        assertEquals(1L, t.get(1L));
    }

    @Test
    void threadIdIsSmallestDbIdInThread() {
        Map<Long, Long> t = threader.computeThreads(List.of(
                in(9, "<root@x>", null, null),
                in(3, "<kid@x>", "<root@x>", "<root@x>")));
        assertEquals(3L, t.get(9L));
        assertEquals(3L, t.get(3L));
    }
}
