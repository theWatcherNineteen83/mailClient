package de.metis.mail.model;

import java.util.logging.Logger;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MailAddress {
    private static final Logger LOG = Logger.getLogger(MailAddress.class.getName());
    private static final Pattern LOCAL_PART_PATTERN = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$");
    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("^[\\w\\s'\\-]{1,64}$");

    private final String displayName;
    private final String localPart;
    private final String domain;

    private MailAddress(String displayName, String localPart, String domain) {
        this.displayName = displayName;
        this.localPart = localPart;
        this.domain = domain;
    }

    public static MailAddress parse(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new IllegalArgumentException("Mail address must not be null or empty");
        }
        String address = rawAddress.trim();
        String namePart = null;
        String emailAddress = address;

        int ltIndex = address.lastIndexOf('<');
        int gtIndex = address.indexOf('>');
        if (ltIndex >= 0 && gtIndex > ltIndex) {
            if (gtIndex != address.length() - 1) {
                throw new IllegalArgumentException("Malformed RFC5322 address: missing closing '>'");
            }
            namePart = address.substring(0, ltIndex).trim();
            emailAddress = address.substring(ltIndex + 1, gtIndex).trim();
        } else if (address.contains("<")) {
            throw new IllegalArgumentException("Malformed RFC5322 address: mismatched angle brackets");
        }

        if (emailAddress.isEmpty()) {
            throw new IllegalArgumentException("Mail address is empty");
        }

        int atIndex = emailAddress.lastIndexOf('@');
        if (atIndex <= 0 || atIndex == emailAddress.length() - 1) {
            throw new IllegalArgumentException("Invalid mail address format: missing or malformed '@'");
        }

        String localPart = emailAddress.substring(0, atIndex);
        String domain = emailAddress.substring(atIndex + 1);

        if (localPart.length() > 64) {
            throw new IllegalArgumentException("Local part exceeds 64 characters: " + localPart);
        }
        if (!LOCAL_PART_PATTERN.matcher(localPart).matches()) {
            throw new IllegalArgumentException("Invalid characters in local part: " + localPart);
        }
        if (domain.length() > 253) {
            throw new IllegalArgumentException("Domain exceeds 253 characters");
        }
        if (!DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new IllegalArgumentException("Invalid domain format: " + domain);
        }

        if (namePart != null) {
            if (namePart.isEmpty()) {
                namePart = null;
            } else if (namePart.length() > 64) {
                throw new IllegalArgumentException("Display name exceeds 64 characters: " + namePart);
            } else if (!DISPLAY_NAME_PATTERN.matcher(namePart).matches()) {
                throw new IllegalArgumentException("Invalid characters in display name: " + namePart);
            }
        }

        return new MailAddress(namePart, localPart, domain);
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLocalPart() {
        return localPart;
    }

    public String getDomain() {
        return domain;
    }

    public String toFormattedString() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName + " <" + localPart + "@" + domain + ">";
        }
        return localPart + "@" + domain;
    }

    public String toAddressString() {
        return localPart + "@" + domain;
    }

    @Override
    public String toString() {
        return toFormattedString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MailAddress that = (MailAddress) o;
        return Objects.equals(displayName, that.displayName)
                && localPart.equalsIgnoreCase(that.localPart)
                && domain.equalsIgnoreCase(that.domain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                displayName == null ? null : displayName.toLowerCase(),
                localPart.toLowerCase(),
                domain.toLowerCase()
        );
    }
}