package app.wisprail.storage;

import app.wisprail.profile.ProfileSecrets;
import java.io.IOException;

public interface SecretStore {
  String save(ProfileSecrets secrets) throws IOException;

  ProfileSecrets read(String reference) throws IOException;

  void delete(String reference) throws IOException;
}
