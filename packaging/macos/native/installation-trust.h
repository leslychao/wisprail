#ifndef WISPRAIL_INSTALLATION_TRUST_H
#define WISPRAIL_INSTALLATION_TRUST_H

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/acl.h>
#include <fts.h>
#include <limits.h>
#include <fcntl.h>

static bool protectedObject(const char *path, const struct stat *info) {
  filesec_t security = filesec_init();
  if (security == NULL) return false;
  struct stat current;
  int hasAcl = 0;
  if (statx_np(path, &current, security) != 0
      || current.st_dev != info->st_dev || current.st_ino != info->st_ino
      || current.st_uid != 0 || (current.st_mode & S_IWOTH) != 0
      || ((current.st_mode & S_IWGRP) != 0 && current.st_gid != 0 && current.st_gid != 80)
      || filesec_query_property(security, FILESEC_ACL, &hasAcl) != 0) {
    filesec_free(security);
    return false;
  }
  // macOS represents an absent extended ACL separately from a failed security query.
  if (!hasAcl) {
    filesec_free(security);
    return true;
  }
  acl_t acl = NULL;
  int aclResult = filesec_get_property(security, FILESEC_ACL, &acl);
  filesec_free(security);
  if (aclResult != 0 || acl == NULL) return false;
  acl_entry_t entry;
  int found = acl_get_entry(acl, ACL_FIRST_ENTRY, &entry);
  while (found == 0) {
    acl_tag_t tag;
    acl_permset_t permissions;
    if (acl_get_tag_type(entry, &tag) != 0 || acl_get_permset(entry, &permissions) != 0) {
      acl_free(acl);
      return false;
    }
    if (tag == ACL_EXTENDED_ALLOW &&
        (acl_get_perm_np(permissions, ACL_WRITE_DATA) == 1
         || acl_get_perm_np(permissions, ACL_APPEND_DATA) == 1
         || acl_get_perm_np(permissions, ACL_DELETE) == 1
         || acl_get_perm_np(permissions, ACL_DELETE_CHILD) == 1
         || acl_get_perm_np(permissions, ACL_WRITE_ATTRIBUTES) == 1
         || acl_get_perm_np(permissions, ACL_WRITE_EXTATTRIBUTES) == 1
         || acl_get_perm_np(permissions, ACL_WRITE_SECURITY) == 1
         || acl_get_perm_np(permissions, ACL_CHANGE_OWNER) == 1)) {
      // PKG needs no granting ACL. Reject an unexpected grant rather than guess its membership.
      acl_free(acl);
      return false;
    }
    found = acl_get_entry(acl, ACL_NEXT_ENTRY, &entry);
  }
  acl_free(acl);
  return true;
}

static bool wr_trusted_installation(const char *bundle) {
  if (strcmp(bundle, "/Applications/Wisprail.app") != 0) return false;
  struct stat applications;
  if (lstat("/Applications", &applications) != 0 || !S_ISDIR(applications.st_mode)
      || !protectedObject("/Applications", &applications)) return false;
  char canonical[PATH_MAX];
  if (realpath(bundle, canonical) == NULL
      || strcmp(canonical, bundle) != 0) return false;
  char *roots[] = {canonical, NULL};
  FTS *tree = fts_open(roots, FTS_PHYSICAL | FTS_NOCHDIR, NULL);
  if (tree == NULL) return false;
  bool trusted = true;
  unsigned count = 0;
  FTSENT *item;
  while ((item = fts_read(tree)) != NULL) {
    if (item->fts_info == FTS_DP) continue;
    if (++count > 20000 || item->fts_info == FTS_ERR || item->fts_info == FTS_NS
        || item->fts_info == FTS_DNR || item->fts_statp == NULL) {
      trusted = false;
      break;
    }
    if (item->fts_info == FTS_SL || item->fts_info == FTS_SLNONE) {
      char destination[PATH_MAX];
      struct stat target;
      size_t prefix = strlen(canonical);
      if (item->fts_statp->st_uid != 0 || realpath(item->fts_path, destination) == NULL
          || strncmp(destination, canonical, prefix) != 0 || destination[prefix] != '/'
          || stat(destination, &target) != 0 || !protectedObject(destination, &target)) {
        trusted = false;
        break;
      }
    } else if (!protectedObject(item->fts_path, item->fts_statp)) {
      trusted = false;
      break;
    }
  }
  fts_close(tree);
  return trusted;
}

#endif
