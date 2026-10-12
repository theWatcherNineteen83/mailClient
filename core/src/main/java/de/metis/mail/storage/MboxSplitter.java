package de.metis.mail.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an mbox file into individual EML messages.
 * Uses >From delimiter (RFC 4180).
 */
public class MboxSplitter {

    private static final String MBOX_DELIMITER = ">From ";

    /**
     * Split an mbox file into individual EML strings.
     *
     * @param mboxPath Path to the mbox file
     * @return List of EML message strings
     * @throws IOException If file cannot be read
     */
    public List<String> split(Path mboxPath) throws IOException {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        try (BufferedReader reader = Files.newBufferedReader(mboxPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(MBOX_DELIMITER)) {
                    if (current.length() > 0) {
                        // Trim trailing whitespace from previous message
                        while (current.length() > 0 && Character.isWhitespace(current.charAt(current.length() - 1))) {
                            current.setLength(current.length() - 1);
                        }
                        messages.add(current.toString());
                        current = new StringBuilder();
                    }
                    // Convert >From to From
                    current.append("From ").append(line.substring(MBOX_DELIMITER.length())).append("\n");
                } else {
                    current.append(line).append("\n");
                }
            }
        }

        // Don't forget the last message
        if (current.length() > 0) {
            while (current.length() > 0 && Character.isWhitespace(current.charAt(current.length() - 1))) {
                current.setLength(current.length() - 1);
            }
            messages.add(current.toString());
        }

        return messages;
    }

    /**
     * Split an mbox string into individual EML strings.
     *
     * @param mboxContent The mbox content
     * @return List of EML message strings
     */
    public List<String> split(String mboxContent) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        String[] lines = mboxContent.split("\n");
        for (String line : lines) {
            if (line.startsWith(MBOX_DELIMITER)) {
                trimTrailingWhitespace(current);
                if (current.length() > 0) {
                    messages.add(current.toString());
                }
                current = new StringBuilder();
                current.append("From ").append(line.substring(MBOX_DELIMITER.length())).append("\n");
            } else {
                current.append(line).append("\n");
            }
        }

        trimTrailingWhitespace(current);
        if (current.length() > 0) {
            messages.add(current.toString());
        }

        return messages;
    }

    private static void trimTrailingWhitespace(StringBuilder sb) {
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
    }
}