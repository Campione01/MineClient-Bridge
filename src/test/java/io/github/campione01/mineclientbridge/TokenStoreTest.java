package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void tokenIsGeneratedOnceAndReloaded() throws Exception {
        Path tokenPath = temporaryDirectory.resolve("mineclient-bridge.token");
        String first = TokenStore.loadOrCreate(tokenPath);
        String second = TokenStore.loadOrCreate(tokenPath);

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(TokenStore.isValidToken(first));
        assertTrue(Files.isRegularFile(tokenPath));
        assertFalse(Files.isSymbolicLink(tokenPath));

        PosixFileAttributeView posix = Files.getFileAttributeView(
                tokenPath,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    posix.readAttributes().permissions());
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(
                    tokenPath,
                    AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            assertEquals(1, acl.getAcl().size());
            assertEquals(Files.getOwner(tokenPath), acl.getAcl().getFirst().principal());
        }
    }
}
