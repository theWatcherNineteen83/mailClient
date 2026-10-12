package de.metis.mail.spam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpamHeuristicScorerTest {

    private final SpamHeuristicScorer scorer = new SpamHeuristicScorer();

    private static final String SPAM_BODY =
            "Klicken Sie sofort hier http://a.biz/1 http://a.biz/2 http://a.biz/3 http://a.biz/4 http://a.biz/5";

    @Test
    void obviousSpamScoresAbove60() {
        int score = scorer.score(
                "Alice <alice@example.com>", "<alice@other.biz>",
                "URGENT: VERIFY YOUR ACCOUNT NOW", SPAM_BODY, false);
        assertTrue(score > 60, "expected >60, got " + score);
    }

    @Test
    void cleanMailScoresLow() {
        int score = scorer.score(
                "Alice <alice@example.com>", "<alice@example.com>",
                "Hi Bob", "Hope you're well", true);
        assertTrue(score < 30, "expected <30, got " + score);
    }

    @Test
    void senderMismatchAddsPoints() {
        int match = scorer.score("A <a@example.com>", "<a@example.com>", "s", "b", true);
        int mismatch = scorer.score("A <a@example.com>", "<a@other.com>", "s", "b", true);
        assertTrue(mismatch > match);
    }

    @Test
    void suspiciousTldInUrlHostIsDetected() {
        int withUrl = scorer.score(null, null, "s",
                "http://shop.click/x http://a.top/y", true);
        int without = scorer.score(null, null, "s",
                "http://shop.example.de/x", true);
        assertTrue(withUrl > without);
    }

    @Test
    void uppercaseSubjectAddsPoints() {
        int upper = scorer.score(null, null, "JETZT SOFORT KONTO BESTAETIGE", "x", true);
        int normal = scorer.score(null, null, "Nur eine Info", "x", true);
        assertTrue(upper > normal);
    }

    @Test
    void scoreIsCappedAt100() {
        int score = scorer.score(
                "A <a@example.com>", "<a@evil.biz>",
                "URGENT VERIFY ACCOUNT PASSWORD NOW",
                SPAM_BODY + " http://x.top/6 http://x.link/7 http://x.page/8 http://x.site/9 http://x.click/10",
                false);
        assertTrue(score <= 100);
    }

    @Test
    void nullInputsDoNotThrow() {
        assertDoesNotThrow(() -> scorer.score(null, null, null, null, false));
    }
}
