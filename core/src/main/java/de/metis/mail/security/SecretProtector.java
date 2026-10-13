package de.metis.mail.security;

/** Kapselt Ver- und Entschluesselung von Geheimnissen (z. B. Konto-Passwoertern). */
public interface SecretProtector {

    /** Identitaet ohne Verschluesselung (Default, u. a. fuer Tests). */
    SecretProtector NONE = new SecretProtector() {
        @Override public boolean isProtected(String stored) { return false; }
        @Override public String protect(String plain) { return plain; }
        @Override public String reveal(String stored) { return stored; }
    };

    /** true, wenn der abgelegte Wert verschluesselt ist. */
    boolean isProtected(String stored);

    /** Klartext -> ablagefaehiger Wert (null/leer bleiben unveraendert). */
    String protect(String plain);

    /** Ablagewert -> Klartext (Altbestand im Klartext wird unveraendert durchgereicht). */
    String reveal(String stored);
}
