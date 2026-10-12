package de.metis.mail.desktop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Persistiert UI-Einstellungen (Layout-Wahl, Sprache) in SQLite —
 * eigenes Key/Value-Table in derselben DB-Datei wie die Mails.
 */
public final class UiSettings implements AutoCloseable {

    private final Connection conn;

    public UiSettings(String jdbcUrl) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl);
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ui_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    public String get(String key, String fallback) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM ui_settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            System.err.println("UiSettings.get(" + key + "): " + e);
        }
        return fallback;
    }

    public void put(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ui_settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("UiSettings.put(" + key + "): " + e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
