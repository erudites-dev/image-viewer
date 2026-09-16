package dev.erudites.mods.imageviewer.client.cache;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class DiskImageCache {

    private static final String TEMP_SUFFIX = ".tmp";
    private static final byte[] EMPTY = new byte[0];

    private record CachedFile(Path path, long size, FileTime lastModified) {}

    private final Path directory;
    private final long maxBytes;

    public DiskImageCache(final Path directory, final long maxBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    public byte[] read(final String hash, final int expectedSize) {
        if (!ImageHashes.isValid(hash)) {
            return EMPTY;
        }
        Path path = this.directory.resolve(hash);
        try {
            if (Files.size(path) != expectedSize) {
                Files.deleteIfExists(path);
                return EMPTY;
            }
            byte[] data = Files.readAllBytes(path);
            if (!ImageHashes.sha256(data).equals(hash)) {
                Files.deleteIfExists(path);
                return EMPTY;
            }
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
            return data;
        } catch (NoSuchFileException e) {
            return EMPTY;
        } catch (IOException e) {
            ImageViewer.LOGGER.warn("Failed to read cached image {}", path, e);
            return EMPTY;
        }
    }

    public void write(final String hash, final byte[] data) {
        if (!ImageHashes.isValid(hash) || data.length > this.maxBytes) {
            return;
        }
        Path target = this.directory.resolve(hash);
        Path temp = this.directory.resolve(hash + TEMP_SUFFIX);
        try {
            Files.createDirectories(this.directory);
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            ImageViewer.LOGGER.warn("Failed to write cached image {}", target, e);
            deleteQuietly(temp);
            return;
        }
        this.prune();
    }

    public void prune() {
        List<CachedFile> files = new ArrayList<>();
        long total = 0;
        try (Stream<Path> stream = Files.list(this.directory)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                String name = path.getFileName().toString();
                if (!ImageHashes.isValid(name)) {
                    deleteQuietly(path);
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) {
                    continue;
                }
                files.add(new CachedFile(path, attributes.size(), attributes.lastModifiedTime()));
                total += attributes.size();
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            ImageViewer.LOGGER.warn("Failed to prune image cache {}", this.directory, e);
            return;
        }

        if (total <= this.maxBytes) {
            return;
        }
        files.sort(Comparator.comparing(CachedFile::lastModified));
        for (CachedFile file : files) {
            if (total <= this.maxBytes) {
                break;
            }
            if (deleteQuietly(file.path())) {
                total -= file.size();
            }
        }
    }

    private static boolean deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            ImageViewer.LOGGER.debug("Failed to delete {}", path, e);
            return false;
        }
    }
}
