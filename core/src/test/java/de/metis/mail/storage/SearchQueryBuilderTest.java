package de.metis.mail.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer den von Metis erzeugten und an dieses Schema angepassten
 * SearchQueryBuilder (Metis-Ziel 13.10.2026, Integration 13.10.).
 */
class SearchQueryBuilderTest {

    private final SearchQueryBuilder builder = new SearchQueryBuilder();

    @Test
    void emptyAndNullInputAreNeutral() {
        SearchQueryBuilder.SearchQuery empty = builder.build("");
        assertEquals(SearchQueryBuilder.NEUTRAL, empty.sql());
        assertTrue(empty.parameters().isEmpty());

        SearchQueryBuilder.SearchQuery nil = builder.build(null);
        assertEquals(SearchQueryBuilder.NEUTRAL, nil.sql());
        assertTrue(nil.parameters().isEmpty());
    }

    @Test
    void fromPrefixUsesRealColumns() {
        SearchQueryBuilder.SearchQuery q = builder.build("from:alice");
        assertTrue(q.sql().contains("from_addr LIKE ?"), q.sql());
        assertTrue(q.sql().contains("from_name LIKE ?"), q.sql());
        assertFalse(q.sql().contains("sender"), "Spalte sender existiert im Schema nicht");
        assertEquals(java.util.List.of("%alice%", "%alice%"), q.parameters());
    }

    @Test
    void subjectPrefixIsSingleClause() {
        SearchQueryBuilder.SearchQuery q = builder.build("subject:Rechnung");
        assertTrue(q.sql().startsWith("subject LIKE ?"), q.sql());
        assertEquals(java.util.List.of("%Rechnung%"), q.parameters());
    }

    @Test
    void attachmentFilterUsesExistsClause() {
        SearchQueryBuilder.SearchQuery yes = builder.build("has:attachment:ja");
        assertTrue(yes.sql().contains("EXISTS (SELECT 1 FROM attachments a WHERE a.message_pk = messages.id)"), yes.sql());
        assertFalse(yes.sql().startsWith("NOT"));
        assertTrue(yes.parameters().isEmpty());

        SearchQueryBuilder.SearchQuery no = builder.build("has:attachment:no");
        assertTrue(no.sql().startsWith("NOT EXISTS"), no.sql());
    }

    @Test
    void fullTextSearchCoversSubjectSenderAndPreview() {
        SearchQueryBuilder.SearchQuery q = builder.build("projekt");
        assertTrue(q.sql().contains("subject LIKE ?"));
        assertTrue(q.sql().contains("from_addr LIKE ?"));
        assertTrue(q.sql().contains("preview LIKE ?"));
        assertEquals(3, q.parameters().size());
        assertTrue(q.parameters().stream().allMatch(p -> p.equals("%projekt%")));
    }

    @Test
    void likeWildcardsAreEscaped() {
        SearchQueryBuilder.SearchQuery q = builder.build("50%_off");
        assertEquals("%50\\%\\_off%", q.parameters().get(0));
        assertTrue(q.sql().contains("ESCAPE '\\'"), q.sql());
    }

    @Test
    void injectionAttemptStaysInParameters() {
        String evil = "'; DROP TABLE messages; --";
        SearchQueryBuilder.SearchQuery q = builder.build(evil);
        assertFalse(q.sql().contains("DROP"), "Nutzereingabe darf nie im SQL landen");
        assertFalse(q.sql().contains(";"));
        assertTrue(q.parameters().contains("%" + evil + "%"));
    }

    @Test
    void prefixWithoutValueFallsBackToFullText() {
        SearchQueryBuilder.SearchQuery q = builder.build("from:");
        assertEquals(SearchQueryBuilder.NEUTRAL, q.sql());

        SearchQueryBuilder.SearchQuery q2 = builder.build("subject: Bericht");
        assertTrue(q2.sql().startsWith("subject LIKE ?"));
    }
}
