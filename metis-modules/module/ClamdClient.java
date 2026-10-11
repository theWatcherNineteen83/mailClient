package de.metis.mail.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClamdClient {
    private static final Logger LOG = Logger.getLogger(ClamdClient.class.getName());
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int INCR_TIMEOUT = 25;
    private static final Pattern VERDICT_PATTERN = Pattern.compile("^(.*)\\s+(OK|FOUND|VIRUS|INFECTED|STREAMERROR|ERROR|INSTREAMERROR|EACCES)$");

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;

    public ClamdClient(String host, int port, int connectTimeoutMs, int socketTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public boolean ping() {
        try (Socket socket = connect()) {
            socket.getOutputStream().write("PING\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String response = readLine(socket.getInputStream());
            return "PONG: ClamAV" .equals(response) || response.startsWith("PONG: ClamAV");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Clamd ping failed", e);
            return false;
        }
    }

    public ClamVerdict scan(byte[] data) {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(("INCR\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();

            String incrResponse = readLine(in);
            if (!"STREAM: INCR".equals(incrResponse)) {
                throw new IOException("Invalid INCR response: " + incrResponse);
            }

            if (data != null) {
                int offset = 0;
                while (offset < data.length) {
                    int len = Math.min(CHUNK_SIZE, data.length - offset);
                    out.write(data, offset, len);
                    out.flush();
                    offset += len;

                    String chunkResponse = readLine(in);
                    if (!chunkResponse.equals("OK")) {
                        throw new IOException("Invalid chunk response: " + chunkResponse);
                    }
                }
            }

            out.write(("END\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();

            String verdictLine = readLine(in);
            return parseVerdict(verdictLine);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Clamd scan failed", e);
            return new ClamVerdict(false, "Error during scan: " + e.getMessage());
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(socketTimeoutMs);
        return socket;
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            sb.append((char) c);
        }
        if (sb.isEmpty()) {
            return "";
        }
        return sb.toString();
    }

    private ClamVerdict parseVerdict(String line) {
        if (line == null || line.isEmpty()) {
            return new ClamVerdict(false, "Empty response");
        }

        Matcher matcher = VERDICT_PATTERN.matcher(line.trim());
        if (matcher.matches()) {
            String fileName = matcher.group(1);
            String status = matcher.group(2);

            boolean isInfected = "FOUND".equalsIgnoreCase(status) || "VIRUS".equalsIgnoreCase(status) || "INFECTED".equalsIgnoreCase(status);
            
            if (status.equals("OK")) {
                return new ClamVerdict(true, "OK");
            } else if (isInfected) {
                return new ClamVerdict(false, "VIRUS:" + fileName);
            } else {
                return new ClamVerdict(false, status + ":" + fileName);
            }
        }

        return new ClamVerdict(false, "UNPARSEABLE:" + line);
    }

    public static class ClamVerdict {
        private final boolean clean;
        private final String detail;

        public ClamVerdict(boolean clean, String detail) {
            this.clean = clean;
            this.detail = detail;
        }

        public boolean isClean() {
            return clean;
        }

        public String getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return "ClamVerdict{" +
                    "clean=" + clean +
                    ", detail='" + detail + '\'' +
                    '}';
        }
    }
}