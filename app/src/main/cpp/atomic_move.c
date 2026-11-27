#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

/* Open each ancestor without following links. The final rename is relative to
 * the resulting directory descriptors, even if a pathname is then replaced. */
static int open_directory(char *path) {
    int fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    char *state = NULL;
    for (char *part = strtok_r(path, "/", &state); fd >= 0 && part;
         part = strtok_r(NULL, "/", &state)) {
        int next = openat(fd, part, O_PATH | O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC);
        int error = errno;
        close(fd);
        fd = next;
        errno = error;
    }
    return fd;
}

static int move_entry(char *from, char *to) {
    char *old_name = strrchr(from, '/');
    char *new_name = strrchr(to, '/');
    if (!old_name || !new_name || !old_name[1] || !new_name[1]) return EINVAL;
    *old_name++ = '\0';
    *new_name++ = '\0';

    int source = open_directory(from);
    if (source < 0) return errno;
    int target = open_directory(to);
    if (target < 0) {
        int error = errno;
        close(source);
        return error;
    }

    int error;
    struct stat entry;
    if (fstatat(source, old_name, &entry, AT_SYMLINK_NOFOLLOW) < 0) {
        error = errno;
    } else if (!S_ISREG(entry.st_mode) && !S_ISDIR(entry.st_mode)) {
        error = ELOOP;
    } else {
#ifdef SYS_renameat2
        error = syscall(SYS_renameat2, source, old_name, target, new_name, RENAME_NOREPLACE) == 0
            ? 0 : errno;
#else
        error = ENOSYS;
#endif
    }
    close(target);
    close(source);
    return error;
}

JNIEXPORT jint JNICALL
Java_com_tomclaw_appsend_tvlink_TvAtomicMove_renameNoReplace(
    JNIEnv *env, jobject self, jbyteArray from, jbyteArray to
) {
    (void) self;
    char source[4096], target[4096];
    jsize source_length = (*env)->GetArrayLength(env, from);
    jsize target_length = (*env)->GetArrayLength(env, to);
    if (source_length >= sizeof(source) || target_length >= sizeof(target)) return ENAMETOOLONG;
    (*env)->GetByteArrayRegion(env, from, 0, source_length, (jbyte *) source);
    (*env)->GetByteArrayRegion(env, to, 0, target_length, (jbyte *) target);
    if ((*env)->ExceptionCheck(env)) return EINVAL;
    source[source_length] = '\0';
    target[target_length] = '\0';
    return move_entry(source, target);
}
