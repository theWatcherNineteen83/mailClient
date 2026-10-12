package de.metis.mail.module;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * ClamAV client using the real clamd TCP protocol (port 3310).
 *
 * <p>Wichtige Protokoll-Erkenntnis (Live-Test gegen echten clamd, 12.10.2026):
 * clamd unterscheidet zwei Kommando-Modi:
 * <ul>
 *   <li>Zeilen-Terminator (\n): <b>Single-Command-Modus</b> — clamd antwortet und
 *       SCHLIESST die Verbindung danach.</li>
 *   <li>NULL-Terminator (\0): Persistent-Modus — Verbindung bleibt offen.</li>
 * </ul>
 * Ein reiner Mock-Server akzeptierte mehrere \n-Kommandos pro Verbindung, der echte
 * clamd tat das NICHT (broken pipe ab Kommando 2). Dieser Client verwendet daher
 * bewusst pro Kommando eine frische Verbindung — das ist auf JEDER clamd-Version
 * korrekt. INSTREAM bleibt ein Vorgang pro Verbindung (Kommando + Chunks + Antwort
 * gehören auf dieselbe Socket).</p>
 */
public class ClamdClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClamdClient.class.getName());
    private static final int CHUNK_SIZE = 4096;

    private final String host;
    private final int port;

    public ClamdClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Prüft, ob clamd erreichbar ist (reine Verbindungsaufnahme — clamd schließt
     * im Zeilen-Modi die Verbindung nach jedem Kommando, deshalb hält dieser
     * Client keine Verbindung).
     */
    public void connect() throws IOException {
        try (Socket probe = openSocket()) {
            LOG.info("clamd reachable at " + host + ":" + port);
        }
    }

    /** PING -> PONG; true wenn der Daemon lebt. */
    public boolean ping() throws IOException {
        String response = oneShot("PING");
        return response != null && response.equalsIgnoreCase("PONG");
    }

    /** VERSION -> z.B. "ClamAV 1.4.2/27600/...". */
    public String version() throws IOException {
        return oneShot("VERSION");
    }

    /**
     * Streamt Daten zum Scan via INSTREAM.
     * Protokoll: "INSTREAM\n", dann Chunks als
     * [4-Byte Big-Endian Länge][Payload], terminiert durch Zero-Length-Chunk.
     * Antwort: "stream: OK" oder "stream: &lt;Signature&gt; FOUND".
     */
    public String streamScan(InputStream fileData) throws IOException {
        try (Socket socket = openSocket()) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            out.write("nINSTREAM\n".getBytes(StandardCharsets.US_ASCII));

            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = fileData.read(buffer)) != -1) {
                out.write(new byte[]{
                        (byte) (bytesRead >> 24),
                        (byte) (bytesRead >> 16),
                        (byte) (bytesRead >> 8),
                        (byte) bytesRead});
                out.write(buffer, 0, bytesRead);
            }

            // Zero-Length-Chunk = Stream-Ende
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();

            String response = in.readLine();
            LOG.fine("INSTREAM response: " + response);
            return response == null ? null : response.trim();
        }
    }

    /** Ein Kommando pro frischer Verbindung — clamd-Zeilenmodus ist Single-Command. */
    private String oneShot(String command) throws IOException {
        try (Socket socket = openSocket()) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            out.write((command + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = in.readLine();
            LOG.fine(command + " response: " + response);
            return response == null ? null : response.trim();
        }
    }

    private Socket openSocket() throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(30_000);
        return socket;
    }

    @Override
    public void close() {
        // haltunglos: pro Kommando eigene Verbindung
    }
}
