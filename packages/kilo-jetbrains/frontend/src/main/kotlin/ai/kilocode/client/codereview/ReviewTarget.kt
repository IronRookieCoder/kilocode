// kilocode_change - new file
package ai.kilocode.client.codereview

/** Target of a `/review` invocation; args syntax mirrors costrict `reviewContext.ts`. */
sealed interface ReviewTarget {
    /** Bare `/review` — the skill reviews the current working-tree changes. */
    data object Changes : ReviewTarget
    data class File(val relativePath: String) : ReviewTarget
    data class Directory(val relativePath: String) : ReviewTarget
    data class Selection(val relativePath: String, val startLine: Int, val endLine: Int) : ReviewTarget

    companion object {
        /** `@/`-prefixed posix relative path, `@/path:start-end` for selections, empty for changes. */
        fun args(target: ReviewTarget): String = when (target) {
            Changes -> ""
            is File -> "@/${target.relativePath}"
            is Directory -> "@/${target.relativePath}"
            is Selection -> "@/${target.relativePath}:${target.startLine}-${target.endLine}"
        }

        /**
         * Relativize [path] against [root] as a posix path, or null when [path] is not
         * strictly inside [root]. Both inputs may use platform separators.
         */
        fun relative(path: String, root: String): String? {
            val p = path.replace('\\', '/').trimEnd('/')
            val r = root.replace('\\', '/').trimEnd('/')
            if (p == r || !p.startsWith("$r/")) return null
            return p.removePrefix("$r/")
        }

        /** Build a target from editor context; selection wins when non-empty. */
        fun fromEditor(path: String, root: String, selectionLines: IntRange?): ReviewTarget? {
            val rel = relative(path, root) ?: return null
            val lines = selectionLines?.takeIf { !it.isEmpty() } ?: return File(rel)
            return Selection(rel, lines.first, lines.last)
        }

        /** Build a file-or-directory target, or null when [path] is not inside [root]. */
        fun fromView(path: String, isDirectory: Boolean, root: String): ReviewTarget? {
            val rel = relative(path, root) ?: return null
            return if (isDirectory) Directory(rel) else File(rel)
        }
    }
}
