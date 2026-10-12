package de.metis.mail.desktop;

import de.metis.mail.db.StoredMessage;

import javax.swing.table.AbstractTableModel;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tabellenmodell der Mail-Liste. Spaltennamen kommen live aus I18n,
 * damit ein Sprachwechsel ohne Neuaufbau reicht (fireStructureChanged).
 */
public final class MessageTableModel extends AbstractTableModel {

    private static final String[] KEYS = {"col.status", "col.from", "col.subject", "col.date", "col.spam"};
    private static final Class<?>[] TYPES = {String.class, String.class, String.class, String.class, Integer.class};

    private final List<StoredMessage> rows = new ArrayList<>();
    private DateTimeFormatter fmt = formatter("de");

    public void setRows(List<StoredMessage> newRows, String lang) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        fmt = formatter(lang);
        fireTableDataChanged();
    }

    public StoredMessage rowAt(int i) {
        return rows.get(i);
    }

    public int rowCount() { return rows.size(); }

    @Override
    public int getRowCount() { return rows.size(); }

    @Override
    public int getColumnCount() { return KEYS.length; }

    @Override
    public String getColumnName(int col) { return I18n.tr(KEYS[col]); }

    @Override
    public Class<?> getColumnClass(int col) { return TYPES[col]; }

    @Override
    public Object getValueAt(int r, int c) {
        StoredMessage m = rows.get(r);
        return switch (c) {
            case 0 -> (m.unseen() ? I18n.tr("unseen") : " ")
                    + (m.flagged() ? " " + I18n.tr("flagged") : "");
            case 1 -> {
                String name = m.fromName() != null && !m.fromName().isBlank() ? m.fromName() : m.fromAddr();
                yield name == null ? "?" : name;
            }
            case 2 -> m.subject();
            case 3 -> m.sentDate() == null ? "" : fmt.format(m.sentDate());
            default -> m.spamScore();
        };
    }

    private static DateTimeFormatter formatter(String lang) {
        Locale loc = "en".equals(lang) ? Locale.US : Locale.GERMANY;
        return DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
                .withZone(java.time.ZoneId.systemDefault()).withLocale(loc);
    }
}
