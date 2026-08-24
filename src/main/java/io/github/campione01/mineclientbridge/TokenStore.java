package io.github.campione01.mineclientbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

final class TokenStore {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_FILE_BYTES = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern VALID_TOKEN = Pattern.compile("[A-Za-z0-9._~-]{32,512}");

    private TokenStore() {
    }

    static String loadOrCreate(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Token path has no parent directory");
        }
        Files.createDirectories(parent);

        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            String generated = generateToken();
            try {
                Files.writeString(
                        absolute,
                        generated,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                try {
                    restrictToOwner(absolute);
                } catch (IOException e) {
                    Files.deleteIfExists(absolute);
                    throw e;
                }
            } catch (FileAlreadyExistsException ignored) {
                // Another loader won the race; validate the file below.
            }
        }

        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Token path must be a regular file");
        }
        long size = Files.size(absolute);
        if (size <= 0 || size > MAX_TOKEN_FILE_BYTES) {
            throw new IOException("Token file has an invalid size");
        }

        restrictToOwner(absolute);
        String token = Files.readString(absolute, StandardCharsets.UTF_8).trim();
        if (!isValidToken(token)) {
            throw new IOException("Token file contains an invalid token");
        }
        return token;
    }

    static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            token.append(Character.forDigit((value >>> 4) & 0x0F, 16));
            token.append(Character.forDigit(value & 0x0F, 16));
        }
        return token.toString();
    }

    static boolean isValidToken(String token) {
        return token != null && VALID_TOKEN.matcher(token).matches();
    }

    private static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
            return;
        }

        throw new IOException("File system does not expose owner-only permissions for token storage");
    }
}
