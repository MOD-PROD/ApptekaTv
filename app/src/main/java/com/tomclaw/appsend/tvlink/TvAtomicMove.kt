package com.tomclaw.appsend.tvlink

import java.io.File

internal object TvAtomicMove {
    private val available by lazy {
        try {
            val hostLibrary = System.getenv("ATV_MOVE_TEST_LIBRARY")
            if (hostLibrary == null) System.loadLibrary("atv_move") else System.load(hostLibrary)
            true
        } catch (_: LinkageError) {
            false
        }
    }

    fun move(source: File, target: File): String? {
        if (Thread.currentThread().isInterrupted) return "move_interrupted"
        if (!available) return "move_unsupported"
        return when (renameNoReplace(source.path.toByteArray(), target.path.toByteArray())) {
            0 -> null
            1, 13, 30 -> "move_permission" // EPERM, EACCES, EROFS
            2, 20, 40 -> "bad_path" // ENOENT, ENOTDIR, ELOOP
            4 -> "move_interrupted" // EINTR
            17 -> "exists" // EEXIST
            18 -> "move_cross_volume" // EXDEV
            28, 122 -> "move_no_space" // ENOSPC, EDQUOT
            22, 38, 95 -> "move_unsupported" // EINVAL, ENOSYS, EOPNOTSUPP
            else -> "move_failed"
        }
    }

    private external fun renameNoReplace(source: ByteArray, target: ByteArray): Int
}
