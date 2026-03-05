package com.esmp.vector.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Generates deterministic UUID v5 point IDs for Qdrant chunk upserts.
 *
 * <p>UUID v5 uses SHA-1 hashing of a namespace UUID + name to produce a stable, collision-resistant
 * identifier. This mirrors the MERGE-by-business-key pattern used in Neo4j: the same
 * class/method always produces the same UUID, enabling idempotent re-indexing.
 *
 * <p>Namespace: DNS UUID {@code 6ba7b810-9dad-11d1-80b4-00c04fd430c8} per RFC 4122.
 */
public final class ChunkIdGenerator {

  /** RFC 4122 DNS namespace UUID bytes. */
  private static final UUID DNS_NAMESPACE =
      UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

  private ChunkIdGenerator() {}

  /**
   * Produces a deterministic UUID v5 for a code chunk.
   *
   * @param classFqn fully-qualified class name (e.g. {@code com.example.MyClass})
   * @param methodSignature method signature part after {@code #} in the method ID,
   *     or {@code "__HEADER__"} for class-header chunks
   * @return deterministic UUID v5 for use as a Qdrant point ID
   */
  public static UUID chunkId(String classFqn, String methodSignature) {
    String name = classFqn + "#" + methodSignature;
    return uuidV5(DNS_NAMESPACE, name);
  }

  /**
   * Computes UUID v5 per RFC 4122 §4.3.
   *
   * <ol>
   *   <li>Hash the namespace bytes concatenated with the name bytes using SHA-1.
   *   <li>Set version bits to 0x50 (version 5) in octet 6.
   *   <li>Set variant bits to 0x80 in octet 8.
   * </ol>
   */
  private static UUID uuidV5(UUID namespace, String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(uuidToBytes(namespace));
      sha1.update(name.getBytes(StandardCharsets.UTF_8));
      byte[] hash = sha1.digest();

      // Apply version (5) and variant (2) bits
      hash[6] &= 0x0f;
      hash[6] |= 0x50; // version 5
      hash[8] &= 0x3f;
      hash[8] |= 0x80; // variant 10xx

      return bytesToUuid(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 not available", e);
    }
  }

  /** Serialises a UUID to its 16-byte big-endian representation. */
  static byte[] uuidToBytes(UUID uuid) {
    ByteBuffer buf = ByteBuffer.allocate(16);
    buf.putLong(uuid.getMostSignificantBits());
    buf.putLong(uuid.getLeastSignificantBits());
    return buf.array();
  }

  private static UUID bytesToUuid(byte[] bytes) {
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    long msb = buf.getLong();
    long lsb = buf.getLong();
    return new UUID(msb, lsb);
  }
}
