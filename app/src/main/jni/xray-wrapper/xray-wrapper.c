/*
 * Xray Wrapper - Executes libxray.so via dlopen/dlsym
 *
 * Android 10+ mounts all app directories as noexec. We cannot execute binaries.
 * Solution: Load libxray.so via dlopen() and call its main() function.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <errno.h>

#define XRAY_LIB_NAME "libxray.so"

typedef int (*main_func_t)(int, char**, char**);

int main(int argc, char *argv[], char *envp[]) {
    // Get the native library directory where this wrapper is located
    char exe_path[1024];
    ssize_t len = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (len == -1) {
        fprintf(stderr, "Error: Cannot determine executable path: %s\n", strerror(errno));
        return 1;
    }
    exe_path[len] = '\0';

    // Extract directory path
    char *last_slash = strrchr(exe_path, '/');
    if (last_slash == NULL) {
        fprintf(stderr, "Error: Invalid executable path\n");
        return 1;
    }
    *last_slash = '\0';

    // Construct libxray.so path
    char lib_path[2048];
    snprintf(lib_path, sizeof(lib_path), "%s/%s", exe_path, XRAY_LIB_NAME);

    // Check if libxray.so exists
    if (access(lib_path, F_OK) != 0) {
        fprintf(stderr, "Error: %s not found at %s: %s\n", XRAY_LIB_NAME, lib_path, strerror(errno));
        return 1;
    }

    // Load libxray.so
    void *handle = dlopen(lib_path, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        fprintf(stderr, "Error: Cannot load %s: %s\n", lib_path, dlerror());
        return 1;
    }

    // Find main function
    // Xray-core's main is exported as "main"
    dlerror(); // Clear any existing error
    main_func_t xray_main = (main_func_t)dlsym(handle, "main");
    const char *dlsym_error = dlerror();
    if (dlsym_error) {
        fprintf(stderr, "Error: Cannot find main function in %s: %s\n", lib_path, dlsym_error);
        dlclose(handle);
        return 1;
    }

    // Modify argv[0] to point to libxray.so for proper process name
    argv[0] = lib_path;

    // Call Xray's main function
    int ret = xray_main(argc, argv, envp);

    // Clean up
    dlclose(handle);
    return ret;
}







