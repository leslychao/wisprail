package app.wisprail.platform;

import app.wisprail.profile.Profile;
import java.io.IOException;
import java.util.UUID;

/** Owns split-DNS objects; sing-box owns its TUN and routing changes. */
public interface NetworkPlatform {
  NetworkPlan prepare(Profile profile, UUID operationId) throws IOException;

  void checkConflicts(NetworkPlan plan) throws IOException;

  void reserve(NetworkPlan plan) throws IOException;

  void applyDns(NetworkPlan plan, Profile profile) throws IOException;

  void verify(NetworkPlan plan, Profile profile) throws IOException;

  void cleanup(UUID operationId) throws IOException;

  void recover() throws IOException;
}
