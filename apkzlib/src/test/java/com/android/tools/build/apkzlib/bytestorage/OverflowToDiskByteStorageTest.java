package com.android.tools.build.apkzlib.bytestorage;

import com.android.tools.build.apkzlib.zip.utils.CloseableByteSource;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class OverflowToDiskByteStorageTest {

  @Test
  public void testStreamWithinMemory() throws Exception {
    try (OverflowToDiskByteStorage storage =
        new OverflowToDiskByteStorage(
            1024 * 1024, 256 * 1024, TemporaryDirectory::newSystemTemporaryDirectory)) {
      byte[] smallData = new byte[64 * 1024];
      Arrays.fill(smallData, (byte) 0x42);

      try (CloseableByteSource source = storage.fromStream(new ByteArrayInputStream(smallData))) {
        Assert.assertEquals(smallData.length, source.size());
        byte[] readBack;
        try (InputStream is = source.openStream()) {
          readBack = ByteStreams.toByteArray(is);
        }
        Assert.assertArrayEquals(smallData, readBack);
        Assert.assertEquals(smallData.length, storage.getMemoryBytesUsed());
        Assert.assertEquals(0, storage.getDiskBytesUsed());
      }
    }
  }

  @Test
  public void testStreamOverflowToDisk() throws Exception {
    // 64KB max single source memory threshold
    try (OverflowToDiskByteStorage storage =
        new OverflowToDiskByteStorage(
            128 * 1024, 64 * 1024, TemporaryDirectory::newSystemTemporaryDirectory)) {
      byte[] largeData = new byte[256 * 1024];
      for (int i = 0; i < largeData.length; i++) {
        largeData[i] = (byte) (i % 251);
      }

      try (CloseableByteSource source = storage.fromStream(new ByteArrayInputStream(largeData))) {
        Assert.assertEquals(largeData.length, source.size());
        byte[] readBack;
        try (InputStream is = source.openStream()) {
          readBack = ByteStreams.toByteArray(is);
        }
        Assert.assertArrayEquals(largeData, readBack);
        Assert.assertEquals(0, storage.getMemoryBytesUsed());
        Assert.assertEquals(largeData.length, storage.getDiskBytesUsed());
      }
    }
  }

  @Test
  public void testMakeBuilderOverflowToDisk() throws Exception {
    // 64KB max single source memory threshold
    try (OverflowToDiskByteStorage storage =
        new OverflowToDiskByteStorage(
            128 * 1024, 64 * 1024, TemporaryDirectory::newSystemTemporaryDirectory)) {
      byte[] dataPart = new byte[32 * 1024];
      Arrays.fill(dataPart, (byte) 0x33);

      CloseableByteSourceFromOutputStreamBuilder builder = storage.makeBuilder();
      // Write 3 parts -> 96KB, which exceeds the 64KB single source limit
      builder.write(dataPart);
      builder.write(dataPart);
      builder.write(dataPart);

      try (CloseableByteSource source = builder.build()) {
        Assert.assertEquals(dataPart.length * 3, source.size());
        byte[] readBack;
        try (InputStream is = source.openStream()) {
          readBack = ByteStreams.toByteArray(is);
        }
        Assert.assertEquals(dataPart.length * 3, readBack.length);
        Assert.assertEquals(0, storage.getMemoryBytesUsed());
        Assert.assertEquals(dataPart.length * 3, storage.getDiskBytesUsed());
      }
    }
  }
}
