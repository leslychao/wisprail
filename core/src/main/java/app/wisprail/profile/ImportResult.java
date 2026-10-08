package app.wisprail.profile;

import java.util.List;

public record ImportResult(Profile profile, ProfileSecrets secrets, List<String> notes) {
  public ImportResult {
    notes = List.copyOf(notes);
  }
}
