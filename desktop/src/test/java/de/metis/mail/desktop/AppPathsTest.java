package de.metis.mail.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4: Ablageort der Daten — Property schlaegt alles, dann Konfigdatei, dann
 * Portable-Marker neben dem Jar, dann Altbestand, dann OS-Standard.
 */
class AppPathsTest {

    @TempDir
    Path tmp;

    private AppPaths.Env linux(Path home, Path jarDir, String property) {
        return new AppPaths.Env(property, jarDir, home, "linux");
    }

    @Test
    void propertyWinsOverEverything() {
        Path forced = tmp.resolve("erzwungen");
        AppPaths.Env env = linux(tmp, tmp, forced.toString());
        assertEquals(forced.toAbsolutePath(), AppPaths.resolveDataDir(env));
    }

    @Test
    void portableMarkerNextToJarSelectsPortableDir() throws Exception {
        Path jarDir = Files.createDirectories(tmp.resolve("portable-app"));
        Files.writeString(jarDir.resolve("portable.txt"), "portable", StandardCharsets.UTF_8);
        AppPaths.Env env = linux(tmp, jarDir, null);

        assertTrue(AppPaths.isPortableMarked(env));
        assertEquals(jarDir.resolve("MailClient-Daten"), AppPaths.resolveDataDir(env));
    }

    @Test
    void configFileModePortableAndUser() throws Exception {
        Path jarDir = Files.createDirectories(tmp.resolve("app"));
        AppPaths.Env env = linux(tmp, jarDir, null);

        AppPaths.writeMode(env, AppPaths.MODE_PORTABLE);
        assertEquals("portable", AppPaths.readMode(env));
        assertEquals(jarDir.resolve("MailClient-Daten"), AppPaths.resolveDataDir(env));

        AppPaths.writeMode(env, AppPaths.MODE_USER);
        assertEquals(AppPaths.userDir(env), AppPaths.resolveDataDir(env));
        assertEquals(tmp.resolve(".local/share/mailclient"), AppPaths.userDir(env));
    }

    @Test
    void customPathFromConfigFileIsUsed() throws Exception {
        AppPaths.Env env = linux(tmp, null, null);
        Path custom = tmp.resolve("eigener-ort");
        AppPaths.writeMode(env, custom.toString());
        assertEquals(custom.toAbsolutePath(), AppPaths.resolveDataDir(env));
    }

    @Test
    void legacyFolderWinsOnLinuxWithoutConfiguration() throws Exception {
        Path legacy = Files.createDirectories(tmp.resolve("MailClient-Daten"));
        AppPaths.Env env = linux(tmp, null, null);
        assertEquals(legacy, AppPaths.resolveDataDir(env), "bestehende Installation darf nicht umziehen");
    }

    @Test
    void windowsDefaultUsesUserProfile() {
        AppPaths.Env env = new AppPaths.Env(null, null, tmp, "windows 11");
        assertTrue(AppPaths.isWindows(env));
        Path resolved = AppPaths.resolveDataDir(env);
        assertTrue(resolved.startsWith(tmp), "muss im Benutzerprofil landen: " + resolved);
        assertTrue(resolved.toString().endsWith("MailClient"), resolved.toString());
    }
}
