#include <dlfcn.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <jni.h>
#include "native/installation-trust.h"

typedef int (*JliLaunch)(int, char **, int, const char **, int, const char **,
    const char *, const char *, const char *, const char *, jboolean,
    jboolean, jboolean, jint);

int main(int argc, char **argv) {
  char executable[PATH_MAX], resolved[PATH_MAX], library[PATH_MAX];
  char classpath[PATH_MAX], install_option[PATH_MAX], native_option[PATH_MAX];
  uint32_t length = sizeof(executable);
  if (_NSGetExecutablePath(executable, &length) != 0 || realpath(executable, resolved) == NULL) {
    fputs("Cannot locate Wisprail application.\n", stderr);
    return 1;
  }
  char *separator = strrchr(resolved, '/');
  if (!separator) return 1;
  *separator = '\0';
  separator = strrchr(resolved, '/');
  if (!separator) return 1;
  *separator = '\0';
  if (geteuid() == 0) {
    char bundle_relative[PATH_MAX], bundle[PATH_MAX];
    if (snprintf(bundle_relative, sizeof(bundle_relative), "%s/..", resolved) >= sizeof(bundle_relative)
        || realpath(bundle_relative, bundle) == NULL || !wr_trusted_installation(bundle)) {
      fputs("Application ownership could not be verified.\n", stderr);
      return 1;
    }
  }
  if (snprintf(library, sizeof(library), "%s/runtime/Contents/Home/lib/libjli.dylib", resolved) >= sizeof(library)
      || snprintf(classpath, sizeof(classpath), "%s/app/*", resolved) >= sizeof(classpath)
      || snprintf(install_option, sizeof(install_option), "-Dwisprail.install.dir=%s", resolved) >= sizeof(install_option)
      || snprintf(native_option, sizeof(native_option), "-Dwisprail.native.path=%s/app/native", resolved) >= sizeof(native_option)) {
    fputs("Application path is too long.\n", stderr);
    return 1;
  }
  void *library_handle = dlopen(library, RTLD_NOW | RTLD_GLOBAL);
  if (!library_handle) {
    fputs("Bundled Java runtime could not be loaded.\n", stderr);
    return 1;
  }
  JliLaunch launch = (JliLaunch)dlsym(library_handle, "JLI_Launch");
  if (!launch) return 1;
  char **arguments = calloc((size_t)argc + 8, sizeof(char *));
  if (!arguments) return 1;
  int count = 0;
  arguments[count++] = argv[0];
  arguments[count++] = install_option;
  arguments[count++] = native_option;
  arguments[count++] = "-Dapple.awt.application.name=Wisprail";
  arguments[count++] = "-cp";
  arguments[count++] = classpath;
#if defined(WISPRAIL_MAINTENANCE)
  arguments[count++] = "app.wisprail.platform.MacMaintenanceMain";
#elif defined(WISPRAIL_AGENT)
  arguments[count++] = "app.wisprail.agent.AgentMain";
#else
  arguments[count++] = "app.wisprail.ui.Launcher";
#endif
  for (int index = 1; index < argc; index++) arguments[count++] = argv[index];
  int status = launch(count, arguments, 0, NULL, 0, NULL, "21.0.11", "21",
      "Wisprail", "java", JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
  free(arguments);
  return status;
}
