package app.wisprail.profile;

/** The same versioned, secret-free format is used on disk and for explicit export. */
public record ProfileDocument(int formatVersion, Profile profile) {
  public static final int VERSION = 1;

  public ProfileDocument(Profile profile) {
    this(VERSION, profile);
  }
}
