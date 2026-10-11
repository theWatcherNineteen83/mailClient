package de.metis.mail.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MboxSplitter implements Iterator<String>, Iterable<String> {
    private static final Logger LOG = Logger.getLogger(MboxSplitter.class.getName());

    private static final String FROM_MARKER = "From ";
    private static final String ESCAPE_PREFIX = ">From ";
    private static final String FROM_LINE_START = "From:";

    private final Reader reader;
    private final BufferedReader bufferedReader;
    private String currentLine;
    private String pendingLine;
    private List<String> currentMessageLines;
    private boolean messageInProgress;
    private boolean hasNext;

    public MboxSplitter(Reader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("Reader must not be null");
        }
        this.reader = reader;
        this.bufferedReader = new BufferedReader(reader);
        this.currentMessageLines = new ArrayList<>();
        this.messageInProgress = false;
        this.currentLine = null;
        this.pendingLine = null;
        this.hasNext = false;
    }

    private boolean isFromLine(String line) {
        if (line == null || line.length() < FROM_MARKER.length()) {
            return false;
        }
        if (line.startsWith(FROM_MARKER)) {
            return true;
        }
        if (line.startsWith(ESCAPE_PREFIX)) {
            return false;
        }
        return false;
    }

    private String unescapeLine(String line) {
        if (line == null) {
            return null;
        }
        if (line.startsWith(ESCAPE_PREFIX)) {
            return FROM_MARKER + line.substring(ESCAPE_PREFIX.length());
        }
        return line;
    }

    private String readLine() throws IOException {
        return bufferedReader.readLine();
    }

    private boolean ensureHasNext() throws IOException {
        if (hasNext) {
            return true;
        }

        if (pendingLine != null) {
            currentLine = pendingLine;
            pendingLine = null;
        } else {
            currentLine = readLine();
            if (currentLine == null) {
                hasNext = false;
                return false;
            }
        }

        if (isFromLine(currentLine)) {
            if (messageInProgress && !currentMessageLines.isEmpty()) {
                hasNext = true;
                return true;
            }

            messageInProgress = true;
            currentMessageLines.clear();

            String nextLine = readLine();
            if (nextLine == null) {
                if (!currentMessageLines.isEmpty()) {
                    hasNext = true;
                    return true;
                }
                messageInProgress = false;
                hasNext = false;
                return false;
            }

            pendingLine = nextLine;
            hasNext = true;
            return true;
        }

        if (messageInProgress) {
            currentMessageLines.add(unescapeLine(currentLine));

            String nextLine = readLine();
            if (nextLine != null && isFromLine(nextLine)) {
                hasNext = true;
                return true;
            }

            if (nextLine == null) {
                hasNext = true;
                messageInProgress = false;
                return true;
            }

            pendingLine = nextLine;
            hasNext = true;
            return true;
        } else {
            if (currentLine.startsWith(FROM_LINE_START)) {
                messageInProgress = true;
                currentMessageLines.clear();
                currentMessageLines.add(currentLine);

                String nextLine = readLine();
                if (nextLine == null) {
                    hasNext = true;
                    messageInProgress = false;
                    return true;
                }
                pendingLine = nextLine;
                hasNext = true;
                return true;
            }

            String nextLine = readLine();
            if (nextLine == null) {
                hasNext = false;
                return false;
            }
            pendingLine = nextLine;
            hasNext = false;
            return false;
        }
    }

    @Override
    public boolean hasNext() {
        try {
            return ensureHasNext();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Error while checking for next message", e);
            return false;
        }
    }

    @Override
    public String next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more messages");
        }

        List<String> lines = currentMessageLines;
        messageInProgress = false;
        currentMessageLines = new ArrayList<>();
        hasNext = false;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Remove operation is not supported");
    }

    @Override
    public Iterator<String> iterator() {
        return this;
    }

    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error while closing reader", e);
        }
    }
}