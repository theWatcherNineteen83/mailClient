package de.metis.mail.module;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * ClamAV client using the real clamd TCP protocol.
 * Communicates over TCP port 3310.
 */
public class ClamdClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClamdClient.class.getName());
    private static final String TERMINATOR = "\n";
    private static final String COMMAND_PING = "PING";
    private static final String COMMAND_VERSION = "VERSION";
    private static final String COMMAND_INSTREAM = "INSTREAM";

    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private OutputStream writer;

    public ClamdClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Connect to the clamd server.
     */
    public void connect() throws IOException {
        socket = new Socket(host, port);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        writer = socket.getOutputStream();
        LOG.info("Connected to clamd at " + host + ":" + port);
    }

    /**
     * Send PING command to check if clamd is alive.
     * Real response is "PONG" followed by newline.
     */
    public boolean ping() throws IOException {
        sendCommand(COMMAND_PING);
        String response = reader.readLine();
        return "PONG".equalsIgnoreCase(response);
    }

    /**
     * Get clamd version.
     */
    public String version() throws IOException {
        sendCommand(COMMAND_VERSION);
        return reader.readLine();
    }

    /**
     * Stream file content for virus scanning via INSTREAM.
     * Real clamd protocol: send "INSTREAM\n", then data in chunks of
     * [4-byte big-endian length][payload], terminated by a zero-length chunk.
     * Response: "stream: OK" or "stream: <Signature> FOUND".
     */
    public String streamScan(InputStream fileData) throws IOException {
        sendCommand(COMMAND_INSTREAM);

        // Stream the file content in length-prefixed chunks
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = fileData.read(buffer)) != -1) {
            writer.write(new byte[]{
                    (byte) (bytesRead >> 24),
                    (byte) (bytesRead >> 16),
                    (byte) (bytesRead >> 8),
                    (byte) bytesRead});
            writer.write(buffer, 0, bytesRead);
        }

        // Zero-length chunk signals EOF
        writer.write(new byte[]{0, 0, 0, 0});
        writer.flush();

        // Read response
        String response = reader.readLine();
        LOG.fine("INSTREAM response: " + response);
        
        // Real clamd responses: "stream: OK" or "stream: VIRUS FOUND"
        return response;
    }

    private void sendCommand(String command) throws IOException {
        LOG.fine("Sending command: " + command);
        writer.write((command + TERMINATOR).getBytes(StandardCharsets.US_ASCII));
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
            LOG.info("Disconnected from clamd");
        }
    }
}
