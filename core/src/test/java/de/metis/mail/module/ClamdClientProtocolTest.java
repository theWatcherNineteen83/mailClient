package de.metis.mail.module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-004: ClamdClient protocol conformance test against a mock clamd server.
 * The mock implements the real clamd TCP protocol: line commands, PING->PONG,
 * VERSION, and INSTREAM with 4-byte big-endian length-prefixed chunks
 * terminated by a zero-length chunk.
 */
class ClamdClientProtocolTest {

    /** The EICAR test signature string (standard antivirus test file). */
    private static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H$H*";

    private ServerSocket serverSocket;
    private Thread serverThread;
    private final AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
    private volatile boolean framingOk = false;

    @BeforeEach
    void startMockClamd() throws IOException {
        serverSocket = new ServerSocket(0); // random free port
        serverThread = new Thread(this::serveOneConnection);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void stopMockClamd() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    private void serveOneConnection() {
        try (Socket client = serverSocket.accept();
             DataInputStream in = new DataInputStream(client.getInputStream());
             OutputStream out = client.getOutputStream()) {

            while (true) {
                String line = readLine(in);
                if (line == null) break;
                switch (line.trim()) {
                    case "PING" -> out.write("PONG\n".getBytes(StandardCharsets.US_ASCII));
                    case "VERSION" -> out.write(
                            "ClamAV 1.3.1/27400/Mon Oct 12 2026 08:00:00\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    case "INSTREAM" -> {
                        ByteArrayOutputStream payload = new ByteArrayOutputStream();
                        boolean ok = true;
                        while (true) {
                            int len = in.readInt(); // 4-byte big-endian chunk length
                            if (len < 0 || len > 64 * 1024 * 1024) {
                                ok = false; // protocol violation: bogus length
                                break;
                            }
                            if (len == 0) break;    // zero-length chunk = EOF
                            byte[] chunk = new byte[len];
                            in.readFully(chunk);
                            payload.write(chunk);
                        }
                        framingOk = ok;
                        receivedPayload.set(payload.toByteArray());
                        String body = payload.toString(StandardCharsets.US_ASCII);
                        if (body.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")) {
                            out.write("stream: Eicar-Test-Signature FOUND\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                        } else {
                            out.write("stream: OK\n".getBytes(StandardCharsets.US_ASCII));
                        }
                    }
                    default -> out.write(("ERROR: UNKNOWN COMMAND " + line + "\n")
                            .getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
            }
        } catch (IOException e) {
            // connection closed by client — normal
        }
    }

    /** Read a \n-terminated line from a DataInputStream. */
    private String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return buf.toString(StandardCharsets.US_ASCII);
            buf.write(b);
        }
        return buf.size() > 0 ? buf.toString(StandardCharsets.US_ASCII) : null;
    }

    @Test
    void pingReturnsPong() throws IOException {
        try (ClamdClient client = new ClamdClient("127.0.0.1", serverSocket.getLocalPort())) {
            client.connect();
            assertTrue(client.ping());
        }
    }

    @Test
    void versionReturnsClamAvString() throws IOException {
        try (ClamdClient client = new ClamdClient("127.0.0.1", serverSocket.getLocalPort())) {
            client.connect();
            String v = client.version();
            assertNotNull(v);
            assertTrue(v.startsWith("ClamAV"), "expected ClamAV banner, got: " + v);
        }
    }

    @Test
    void instreamCleanDataReturnsOk() throws IOException {
        byte[] data = "harmless file content".getBytes(StandardCharsets.UTF_8);
        try (ClamdClient client = new ClamdClient("127.0.0.1", serverSocket.getLocalPort())) {
            client.connect();
            String result = client.streamScan(new ByteArrayInputStream(data));
            assertEquals("stream: OK", result);
        }
    }

    @Test
    void instreamEicarReturnsFound() throws IOException {
        byte[] data = EICAR.getBytes(StandardCharsets.US_ASCII);
        try (ClamdClient client = new ClamdClient("127.0.0.1", serverSocket.getLocalPort())) {
            client.connect();
            String result = client.streamScan(new ByteArrayInputStream(data));
            assertEquals("stream: Eicar-Test-Signature FOUND", result);
        }
    }

    @Test
    void instreamUsesLengthPrefixedChunksAndZeroEof() throws IOException, InterruptedException {
        // Payload larger than one chunk to exercise multi-chunk framing
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) ('a' + (i % 26));

        try (ClamdClient client = new ClamdClient("127.0.0.1", serverSocket.getLocalPort())) {
            client.connect();
            String result = client.streamScan(new ByteArrayInputStream(data));
            assertEquals("stream: OK", result);
        }

        // Give the server thread a moment to record state
        Thread.sleep(100);
        assertTrue(framingOk, "chunk length prefixes must be valid (4-byte big-endian)");
        assertArrayEquals(data, receivedPayload.get(),
                "server must reassemble exactly the bytes the client streamed");
    }

    @Test
    void sequentialCommandsOnOneConnection() throws IOException {
        try (ClamdClient client = new ClamdClient("127.0.0.1", serverSocket.getLocalPort())) {
            client.connect();
            assertTrue(client.ping());
            assertNotNull(client.version());
            assertEquals("stream: OK",
                    client.streamScan(new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8))));
        }
    }
}
