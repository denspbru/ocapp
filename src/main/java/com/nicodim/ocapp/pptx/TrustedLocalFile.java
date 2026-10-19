package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.support.ConversionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Consumer;

/** Reads one operator-configured regular file without following links or accepting replacement races. */
final class TrustedLocalFile {
    private TrustedLocalFile() { }

    static byte[] read(String configuredPath, String configuredRoot, long maximum,
                       java.util.function.Supplier<ConversionException> untrusted,
                       java.util.function.Supplier<ConversionException> tooLarge) {
        return read(configuredPath, configuredRoot, maximum, untrusted, tooLarge, ignored -> { });
    }

    static byte[] read(String configuredPath, String configuredRoot, long maximum,
                       java.util.function.Supplier<ConversionException> untrusted,
                       java.util.function.Supplier<ConversionException> tooLarge,
                       Consumer<Path> afterOpen) {
        try {
            Path path = Paths.get(configuredPath).normalize();
            if (!path.isAbsolute()) throw untrusted.get();
            Path checked = platformRootAlias(path);
            Path real = checked.toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();
            rejectSymlinkComponents(checked, untrusted);
            if (!real.equals(checked)) throw untrusted.get();
            Path root = configuredRoot == null || configuredRoot.isBlank()
                ? real.getParent() : platformRootAlias(Paths.get(configuredRoot).normalize());
            if (root == null || !root.isAbsolute()) throw untrusted.get();
            rejectSymlinkComponents(root, untrusted);
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();
            if (!realRoot.equals(root) || !real.startsWith(realRoot)) throw untrusted.get();
            BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw untrusted.get();
            if (before.size() > maximum) throw tooLarge.get();
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(32, before.size()), Math.min(maximum, 1_048_576)));
            try (var input = Files.newInputStream(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                afterOpen.accept(real);
                byte[] buffer = new byte[8192]; long count = 0; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (count > maximum - read || count + read > Integer.MAX_VALUE) throw tooLarge.get();
                    count += read; out.write(buffer, 0, read);
                }
            }
            BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!same(before, after) || out.size() != after.size()) throw untrusted.get();
            return out.toByteArray();
        } catch (ConversionException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw untrusted.get(); }
    }

    private static Path platformRootAlias(Path configured) {
        // macOS exposes /var as the fixed system alias /private/var; normalize only that OS-owned root alias.
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
            && configured.startsWith("/var"))
            return Path.of("/private").resolve(configured.toString().substring(1)).normalize();
        return configured;
    }

    private static void rejectSymlinkComponents(Path path, java.util.function.Supplier<ConversionException> untrusted) {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) throw untrusted.get();
        }
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        Object ak = a.fileKey(), bk = b.fileKey();
        return a.isRegularFile() && b.isRegularFile() && a.size() == b.size()
            && a.lastModifiedTime().equals(b.lastModifiedTime())
            && (ak == null || bk == null ? ak == bk : Objects.equals(ak, bk));
    }
}
