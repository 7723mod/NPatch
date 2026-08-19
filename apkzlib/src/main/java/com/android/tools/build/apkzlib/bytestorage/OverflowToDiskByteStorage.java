package com.android.tools.build.apkzlib.bytestorage;

import com.android.tools.build.apkzlib.zip.utils.CloseableByteSource;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/**
 * Byte storage that keeps data in memory up to a certain size. After that, older sources are moved
 * to disk and the newer ones served from memory.
 */
public class OverflowToDiskByteStorage implements ByteStorage {

  /** Size of the default memory cache. */
  private static final long DEFAULT_MEMORY_CACHE_BYTES = 50 * 1024 * 1024;

  /** Maximum size for a single in-memory buffer before immediately spilling to disk. */
  private static final long DEFAULT_MAX_SINGLE_SOURCE_MEMORY_BYTES = 4 * 1024 * 1024;

  /** In-memory storage. */
  private final InMemoryByteStorage memoryStorage;

  /** Disk-based storage. */
  @VisibleForTesting // private otherwise.
  final TemporaryDirectoryStorage diskStorage;

  /** Tracker that keeps all memory sources. */
  private final LruTracker<LruTrackedCloseableByteSource> memorySourcesTracker;

  /** Maximum amount of data to keep in memory. */
  private final long memoryCacheSize;

  /** Maximum size for an individual source in memory. */
  private final long maxSingleSourceMemory;

  /** Maximum amount of data used. */
  private long maxBytesUsed;

  /**
   * Creates a new byte storage with the default memory cache using the provided temporary directory
   * to write data that overflows the memory size.
   *
   * @param temporaryDirectoryFactory the factory used to create a temporary directory where to
   *     overflow to; the created directory will be closed when the {@link
   *     OverflowToDiskByteStorage} object is closed
   * @throws IOException failed to create the temporary directory
   */
  public OverflowToDiskByteStorage(TemporaryDirectoryFactory temporaryDirectoryFactory)
      throws IOException {
    this(DEFAULT_MEMORY_CACHE_BYTES, temporaryDirectoryFactory);
  }

  /**
   * Creates a new byte storage with the given memory cache size using the provided temporary
   * directory to write data that overflows the memory size.
   *
   * @param memoryCacheSize the in-memory cache; a value of {@code 0} will effectively disable
   *     in-memory caching
   * @param temporaryDirectoryFactory the factory used to create a temporary directory where to
   *     overflow to; the created directory will be closed when the {@link
   *     OverflowToDiskByteStorage} object is closed
   * @throws IOException failed to create the temporary directory
   */
  public OverflowToDiskByteStorage(
      long memoryCacheSize, TemporaryDirectoryFactory temporaryDirectoryFactory)
      throws IOException {
    this(
        memoryCacheSize,
        Math.min(Math.max(memoryCacheSize / 4, 1024 * 1024), DEFAULT_MAX_SINGLE_SOURCE_MEMORY_BYTES),
        temporaryDirectoryFactory);
  }

  /**
   * Creates a new byte storage with the given memory cache size and maximum single source memory
   * size using the provided temporary directory.
   */
  public OverflowToDiskByteStorage(
      long memoryCacheSize,
      long maxSingleSourceMemory,
      TemporaryDirectoryFactory temporaryDirectoryFactory)
      throws IOException {
    this.memoryStorage = new InMemoryByteStorage();
    this.diskStorage = new TemporaryDirectoryStorage(temporaryDirectoryFactory);
    this.memoryCacheSize = memoryCacheSize;
    this.maxSingleSourceMemory = maxSingleSourceMemory;
    this.memorySourcesTracker = new LruTracker<>();
  }

  @Override
  public CloseableByteSource fromStream(InputStream stream) throws IOException {
    if (memoryCacheSize <= 0 || maxSingleSourceMemory <= 0) {
      return diskStorage.fromStream(stream);
    }

    ByteArrayOutputStream memBuffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int totalRead = 0;
    boolean overflowed = false;

    while (totalRead <= maxSingleSourceMemory) {
      int toRead = (int) Math.min(chunk.length, maxSingleSourceMemory + 1 - totalRead);
      int r = stream.read(chunk, 0, toRead);
      if (r < 0) {
        break;
      }
      memBuffer.write(chunk, 0, r);
      totalRead += r;
      if (totalRead > maxSingleSourceMemory) {
        overflowed = true;
        break;
      }
    }

    if (overflowed) {
      InputStream combined =
          new SequenceInputStream(new ByteArrayInputStream(memBuffer.toByteArray()), stream);
      return diskStorage.fromStream(combined);
    }

    byte[] data = memBuffer.toByteArray();
    CloseableByteSource memSource =
        new LruTrackedCloseableByteSource(
            memoryStorage.fromStream(new ByteArrayInputStream(data)), memorySourcesTracker);
    checkMaxUsage();
    reviewSources();
    return memSource;
  }

  @Override
  public CloseableByteSourceFromOutputStreamBuilder makeBuilder() throws IOException {
    if (memoryCacheSize <= 0 || maxSingleSourceMemory <= 0) {
      return diskStorage.makeBuilder();
    }

    return new AbstractCloseableByteSourceFromOutputStreamBuilder() {
      private ByteArrayOutputStream memoryBuffer = new ByteArrayOutputStream();
      private CloseableByteSourceFromOutputStreamBuilder diskBuilder = null;
      private boolean overflowed = false;

      @Override
      protected void doWrite(byte[] b, int off, int len) throws IOException {
        if (overflowed) {
          diskBuilder.write(b, off, len);
          return;
        }

        if ((long) memoryBuffer.size() + len > maxSingleSourceMemory) {
          overflowed = true;
          diskBuilder = diskStorage.makeBuilder();
          byte[] buffered = memoryBuffer.toByteArray();
          if (buffered.length > 0) {
            diskBuilder.write(buffered, 0, buffered.length);
          }
          memoryBuffer = null;
          diskBuilder.write(b, off, len);
        } else {
          memoryBuffer.write(b, off, len);
        }
      }

      @Override
      protected CloseableByteSource doBuild() throws IOException {
        if (overflowed) {
          return diskBuilder.build();
        }

        byte[] data = memoryBuffer.toByteArray();
        memoryBuffer = null;
        CloseableByteSource memSource =
            new LruTrackedCloseableByteSource(
                memoryStorage.fromStream(new ByteArrayInputStream(data)), memorySourcesTracker);
        checkMaxUsage();
        reviewSources();
        return memSource;
      }
    };
  }

  @Override
  public CloseableByteSource fromSource(ByteSource source) throws IOException {
    Optional<Long> sizeOpt = source.sizeIfKnown();
    if (sizeOpt.isPresent() && sizeOpt.get() > maxSingleSourceMemory) {
      return diskStorage.fromSource(source);
    }
    try (InputStream is = source.openStream()) {
      return fromStream(is);
    }
  }

  @Override
  public synchronized long getBytesUsed() {
    return memoryStorage.getBytesUsed() + diskStorage.getBytesUsed();
  }

  @Override
  public synchronized long getMaxBytesUsed() {
    return maxBytesUsed;
  }

  /** Checks if we have reached a new high of data usage and set it. */
  private synchronized void checkMaxUsage() {
    if (getBytesUsed() > maxBytesUsed) {
      maxBytesUsed = getBytesUsed();
    }
  }

  /** Checks if any of the sources needs to be written to disk or loaded into memory. */
  private synchronized void reviewSources() throws IOException {
    // Move data from memory to disk until we have at most memoryCacheSize bytes in memory.
    while (memoryStorage.getBytesUsed() > memoryCacheSize) {
      LruTrackedCloseableByteSource last = memorySourcesTracker.last();
      if (last != null) {
        LruTrackedCloseableByteSource lastSource = last;
        lastSource.move(diskStorage);
      } else {
        break;
      }
    }
  }

  /** Obtains the number of bytes stored in memory. */
  public long getMemoryBytesUsed() {
    return memoryStorage.getBytesUsed();
  }

  /** Obtains the maximum number of bytes ever stored in memory. */
  public long getMaxMemoryBytesUsed() {
    return memoryStorage.getMaxBytesUsed();
  }

  /** Obtains the number of bytes stored in disk. */
  public long getDiskBytesUsed() {
    return diskStorage.getBytesUsed();
  }

  /** Obtains the maximum number of bytes ever stored in disk. */
  public long getMaxDiskBytesUsed() {
    return diskStorage.getMaxBytesUsed();
  }

  @Override
  public void close() throws IOException {
    memoryStorage.close();
    diskStorage.close();
  }
}
