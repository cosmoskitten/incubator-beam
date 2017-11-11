package org.apache.beam.sdk.io.aws.s3;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.beam.sdk.io.fs.ResolveOptions;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;

class S3ResourceId implements ResourceId {

  static final String SCHEME = "s3";

  private static final Pattern S3_URI =
      Pattern.compile("(?<SCHEME>[^:]+)://(?<BUCKET>[^/]+)(/(?<KEY>.*))?");

  /**
   * Matches a glob containing a wildcard, capturing the portion before the first wildcard.
   */
  private static final Pattern GLOB_PREFIX = Pattern.compile("(?<PREFIX>[^\\[*?]*)[\\[*?].*");

  private final String bucket;
  private final String key;

  private S3ResourceId(String bucket, String key) {
    checkArgument(!Strings.isNullOrEmpty(bucket), "bucket");
    this.bucket = bucket;
    this.key = checkNotNull(key, "key");
  }

  static S3ResourceId fromComponents(String bucket, String key) {
    return new S3ResourceId(bucket, key);
  }

  static S3ResourceId fromUri(String uri) {
    Matcher m = S3_URI.matcher(uri);
    checkArgument(m.matches(), "Invalid S3 URI: [%s]", uri);
    checkArgument(m.group("SCHEME").equalsIgnoreCase(SCHEME), "Invalid S3 URI scheme: [%s]", uri);
    String bucket = m.group("BUCKET");
    String key = Strings.nullToEmpty(m.group("KEY"));
    if (!key.startsWith("/")) {
      key = "/" + key;
    }
    return fromComponents(bucket, key);
  }

  String getBucket() {
    return bucket;
  }

  String getKey() {
    // Skip leading slash
    return key.substring(1);
  }

  @Override
  public ResourceId resolve(String other, ResolveOptions resolveOptions) {
    checkState(isDirectory(), "Expected this resource to be a directory, but was [%s]", toString());

    if (resolveOptions == StandardResolveOptions.RESOLVE_DIRECTORY) {
      if ("..".equals(other)) {
        if ("/".equals(key)) {
          return this;
        }
        int parentStopsAt = key.substring(0, key.length() - 1).lastIndexOf('/');
        return fromComponents(bucket, key.substring(0, parentStopsAt + 1));
      }

      if ("".equals(other)) {
        return this;
      }

      if (!other.endsWith("/")) {
        other += "/";
      }
      if (S3_URI.matcher(other).matches()) {
        return fromUri(other);
      }
      return fromComponents(bucket, key + other);
    }

    if (resolveOptions == StandardResolveOptions.RESOLVE_FILE) {
      checkArgument(!other.endsWith("/"), "Cannot resolve a file with a directory path: [%s]", other);
      checkArgument(!"..".equals(other), "Cannot resolve parent as file: [%s]", other);
      if (S3_URI.matcher(other).matches()) {
        return fromUri(other);
      }
      return fromComponents(bucket, key + other);
    }

    throw new UnsupportedOperationException(
        String.format("Unexpected StandardResolveOptions [%s]", resolveOptions));
  }

  @Override
  public ResourceId getCurrentDirectory() {
    if (isDirectory()) {
      return this;
    }
    return fromComponents(getBucket(), key.substring(0, key.lastIndexOf('/') + 1));
  }

  @Override
  public String getScheme() {
    return SCHEME;
  }

  @Nullable
  @Override
  public String getFilename() {
    if (!isDirectory()) {
      return key.substring(key.lastIndexOf('/') + 1);
    }
    if ("/".equals(key)) {
      return null;
    }
    String keyWithoutTrailingSlash = key.substring(0, key.length() - 1);
    return keyWithoutTrailingSlash.substring(keyWithoutTrailingSlash.lastIndexOf('/') + 1);
  }

  @Override
  public boolean isDirectory() {
    return key.endsWith("/");
  }

  boolean isWildcard() {
    return GLOB_PREFIX.matcher(getKey()).matches();
  }

  String getKeyNonWildcardPrefix() {
    Matcher m = GLOB_PREFIX.matcher(getKey());
    checkArgument(m.matches(), String.format("Glob expression: [%s] is not expandable.", getKey()));
    return m.group("PREFIX");
  }

  @Override
  public String toString() {
    return String.format("%s://%s%s", SCHEME, bucket, key);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof S3ResourceId)) {
      return false;
    }

    return bucket.equals(((S3ResourceId) obj).bucket) && key.equals(((S3ResourceId) obj).key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(bucket, key);
  }

  // TODO compareTo and test
}
