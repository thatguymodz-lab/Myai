package com.novajarvis.android

import android.content.Context
import java.io.File
import java.util.Locale

enum class JarvisProjectType {
    WEBSITE,
    GAME_2D,
    GAME_3D,
    UNKNOWN
}

data class JarvisProject(
    val id: String,
    val name: String,
    val type: JarvisProjectType,
    val directory: File
)

class ProjectWorkspace(
    private val context: Context
) {

    companion object {

        private const val PREFS_NAME =
            "jarvis_builder"

        private const val KEY_CURRENT_PROJECT =
            "current_project"

        private const val KEY_PREVIEW_PROJECT =
            "preview_project"

        private const val KEY_BUILD_INTERRUPTED =
            "build_interrupted"

        private const val KEY_BUILD_PROJECT =
            "build_project"

        private const val MAIN_FILE =
            "index.html"

        private const val META_FILE =
            "project.meta"

        private const val MAX_BACKUPS =
            10

        /*
         * Protect the app from accidentally trying to save an
         * absurdly large model response.
         */
        private const val MAX_HTML_SIZE =
            1_500_000
    }

    private val prefs =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val root =
        File(
            context.filesDir,
            "jarvis_projects"
        ).apply {
            mkdirs()
        }

    // ============================================================
    // PROJECT TYPE DETECTION
    // ============================================================

    fun detectProjectType(
        request: String
    ): JarvisProjectType {

        val text =
            request.lowercase(
                Locale.getDefault()
            )

        val wants3D =
            listOf(
                "3d game",
                "3d-game",
                "three.js",
                "three js",
                "3d world",
                "3d project"
            ).any {
                text.contains(it)
            }

        val wants2D =
            listOf(
                "2d game",
                "2d-game",
                "2d project",
                "canvas game",
                "platformer",
                "top down game",
                "top-down game"
            ).any {
                text.contains(it)
            }

        val wantsWebsite =
            listOf(
                "website",
                "web site",
                "webpage",
                "web page",
                "landing page",
                "portfolio site",
                "business site"
            ).any {
                text.contains(it)
            }

        val matches =
            listOf(
                wants3D,
                wants2D,
                wantsWebsite
            ).count {
                it
            }

        if (matches != 1) {
            return JarvisProjectType.UNKNOWN
        }

        return when {

            wants3D ->
                JarvisProjectType.GAME_3D

            wants2D ->
                JarvisProjectType.GAME_2D

            wantsWebsite ->
                JarvisProjectType.WEBSITE

            else ->
                JarvisProjectType.UNKNOWN
        }
    }

    // ============================================================
    // REQUEST ROUTING
    // ============================================================

    fun isNewBuildRequest(
        request: String
    ): Boolean {

        val text =
            request.lowercase(
                Locale.getDefault()
            )

        val current =
            getCurrentProject()

        val explicitType =
            detectProjectType(request)

        val newWords =
            listOf(
                "create a new",
                "make a new",
                "build a new",
                "start a new",
                "new website",
                "new 2d game",
                "new 3d game",
                "another website",
                "another game",
                "another project",
                "separate project"
            )

        if (
            newWords.any {
                text.contains(it)
            }
        ) {
            return true
        }

        /*
         * If there is already a project and the user is clearly
         * referring to the current project, treat it as an edit.
         */
        if (
            current != null &&
            (
                text.contains("current") ||
                text.contains("this project") ||
                text.contains("my project") ||
                text.contains("this game") ||
                text.contains("this website")
            )
        ) {
            return false
        }

        /*
         * If no project exists yet, a creation-style request with an
         * explicit project type is a new build.
         */
        if (
            current == null &&
            explicitType !=
                JarvisProjectType.UNKNOWN
        ) {

            val creationWords =
                listOf(
                    "create",
                    "make",
                    "build",
                    "generate",
                    "design",
                    "start"
                )

            if (
                creationWords.any {
                    text.contains(it)
                }
            ) {
                return true
            }
        }

        /*
         * If a project exists and the user explicitly asks to build a
         * DIFFERENT project type, require an obvious creation verb.
         *
         * This makes prompts such as:
         * "build a website"
         * create a website when the current project is a 2D game.
         */
        if (
            current != null &&
            explicitType !=
                JarvisProjectType.UNKNOWN &&
            explicitType !=
                current.type
        ) {

            val creationWords =
                listOf(
                    "create",
                    "make",
                    "build",
                    "generate",
                    "start"
                )

            if (
                creationWords.any {
                    text.contains(it)
                }
            ) {
                return true
            }
        }

        return false
    }

    fun isProjectEditRequest(
        request: String
    ): Boolean {

        val current =
            getCurrentProject()
                ?: return false

        val text =
            request.lowercase(
                Locale.getDefault()
            )

        if (isNewBuildRequest(request)) {
            return false
        }

        val explicitType =
            detectProjectType(request)

        /*
         * Protect the current project from accidentally being
         * overwritten with a different project type.
         */
        if (
            explicitType !=
                JarvisProjectType.UNKNOWN &&
            explicitType !=
                current.type
        ) {
            return false
        }

        val editWords =
            listOf(
                "add ",
                "add a",
                "add an",
                "change",
                "edit",
                "update",
                "modify",
                "remove",
                "replace",
                "fix",
                "improve",
                "make it",
                "make the",
                "put ",
                "more ",
                "less ",
                "bigger",
                "smaller",
                "better",
                "faster",
                "slower",
                "move ",
                "give it",
                "give the",
                "turn it",
                "turn the",
                "background",
                "player",
                "enemy",
                "enemies",
                "score",
                "button",
                "menu",
                "shop",
                "level",
                "levels",
                "sky",
                "ground",
                "controls",
                "character",
                "sprite",
                "graphics"
            )

        return editWords.any {
            text.contains(it)
        }
    }

    // ============================================================
    // PROJECT CREATION
    // ============================================================

    fun createProject(
        requestedName: String,
        type: JarvisProjectType
    ): JarvisProject {

        require(
            type !=
                JarvisProjectType.UNKNOWN
        ) {
            "Project type must be known."
        }

        val safeName =
            sanitizeProjectName(
                requestedName
            )

        val timestamp =
            System.currentTimeMillis()

        val id =
            "${safeName}_$timestamp"

        val directory =
            File(
                root,
                id
            )

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create project directory."
            )
        }

        val project =
            JarvisProject(
                id =
                    id,
                name =
                    requestedName
                        .trim()
                        .ifBlank {
                            defaultProjectName(type)
                        },
                type =
                    type,
                directory =
                    directory
            )

        writeMetadata(project)

        setCurrentProject(project)

        /*
         * Games receive a deterministic working foundation immediately.
         * This means a new game does not depend on the small local AI
         * generating an entire game from scratch.
         */
        when (type) {

            JarvisProjectType.GAME_2D -> {

                saveMainFileAtomic(
                    project,
                    create2DGameFoundation(),
                    makePreviewable =
                        true
                )
            }

            JarvisProjectType.GAME_3D -> {

                saveMainFileAtomic(
                    project,
                    create3DGameFoundation(),
                    makePreviewable =
                        true
                )
            }

            JarvisProjectType.WEBSITE -> {
                /*
                 * Website generation is performed by MainActivity.
                 */
            }

            JarvisProjectType.UNKNOWN -> {
                /*
                 * Already rejected above.
                 */
            }
        }

        return project
    }

    // ============================================================
    // CURRENT / PREVIEW PROJECT
    // ============================================================

    fun getCurrentProject():
        JarvisProject? {

        val id =
            prefs.getString(
                KEY_CURRENT_PROJECT,
                null
            )
                ?: return null

        return loadProject(id)
    }

    fun getPreviewProject():
        JarvisProject? {

        val id =
            prefs.getString(
                KEY_PREVIEW_PROJECT,
                null
            )
                ?: return null

        val project =
            loadProject(id)
                ?: return null

        val html =
            readMainFile(project)
                ?: return null

        val problems =
            validateProject(
                project,
                html
            )

        if (
            problems.any {
                isFatalValidationProblem(it)
            }
        ) {
            return null
        }

        return project
    }

    private fun setCurrentProject(
        project: JarvisProject
    ) {

        /*
         * commit() is intentional here.
         * Project switching should survive an unexpected process death.
         */
        prefs
            .edit()
            .putString(
                KEY_CURRENT_PROJECT,
                project.id
            )
            .commit()
    }

    private fun setPreviewProject(
        project: JarvisProject
    ) {

        val html =
            readMainFile(project)
                ?: throw IllegalStateException(
                    "Cannot verify an empty project."
                )

        val problems =
            validateProject(
                project,
                html
            )

        val fatal =
            problems.any {
                isFatalValidationProblem(it)
            }

        if (fatal) {

            throw IllegalStateException(
                "Project failed validation: " +
                    problems.joinToString("; ")
            )
        }

        prefs
            .edit()
            .putString(
                KEY_PREVIEW_PROJECT,
                project.id
            )
            .commit()
    }

    fun markProjectVerified(
        project: JarvisProject
    ) {

        val html =
            readMainFile(project)
                ?: throw IllegalStateException(
                    "Project has no main file."
                )

        val problems =
            validateProject(
                project,
                html
            )

        if (
            problems.any {
                isFatalValidationProblem(it)
            }
        ) {

            throw IllegalStateException(
                "Project cannot be verified: " +
                    problems.joinToString("; ")
            )
        }

        prefs
            .edit()
            .putString(
                KEY_CURRENT_PROJECT,
                project.id
            )
            .putString(
                KEY_PREVIEW_PROJECT,
                project.id
            )
            .commit()
    }

    // ============================================================
    // BUILD INTERRUPTION / RECOVERY
    // ============================================================

    fun beginBuild(
        project: JarvisProject
    ) {

        prefs
            .edit()
            .putBoolean(
                KEY_BUILD_INTERRUPTED,
                true
            )
            .putString(
                KEY_BUILD_PROJECT,
                project.id
            )
            .commit()
    }

    fun finishBuild(
        project: JarvisProject,
        successful: Boolean
    ) {

        if (successful) {

            /*
             * Verify before clearing the interrupted marker.
             * If verification unexpectedly fails, recovery remains possible.
             */
            markProjectVerified(
                project
            )
        }

        prefs
            .edit()
            .putBoolean(
                KEY_BUILD_INTERRUPTED,
                false
            )
            .remove(
                KEY_BUILD_PROJECT
            )
            .commit()
    }

    fun wasBuildInterrupted():
        Boolean {

        return prefs.getBoolean(
            KEY_BUILD_INTERRUPTED,
            false
        )
    }

    fun getInterruptedProject():
        JarvisProject? {

        val id =
            prefs.getString(
                KEY_BUILD_PROJECT,
                null
            )
                ?: return null

        return loadProject(id)
    }

    fun clearInterruptedBuild() {

        prefs
            .edit()
            .putBoolean(
                KEY_BUILD_INTERRUPTED,
                false
            )
            .remove(
                KEY_BUILD_PROJECT
            )
            .commit()
    }

    // ============================================================
    // FILE ACCESS
    // ============================================================

    fun readMainFile(
        project: JarvisProject
    ): String? {

        val file =
            File(
                project.directory,
                MAIN_FILE
            )

        if (!file.exists()) {
            return null
        }

        return try {

            file.readText(
                Charsets.UTF_8
            )

        } catch (
            _: Exception
        ) {

            null
        }
    }

    fun saveMainFileAtomic(
        project: JarvisProject,
        html: String,
        makePreviewable: Boolean
    ) {

        require(
            html.isNotBlank()
        ) {
            "Generated project is empty."
        }

        /*
         * Normalize predictable structural mistakes before validation.
         *
         * This is NOT an AI repair pass. It only handles deterministic,
         * safe HTML structure such as the viewport meta tag.
         */
        val prepared =
            prepareGeneratedHtml(
                project,
                html
            )

        require(
            prepared.isNotBlank()
        ) {
            "Generated project is empty after normalization."
        }

        require(
            prepared.length <=
                MAX_HTML_SIZE
        ) {
            "Generated project is too large."
        }

        val target =
            File(
                project.directory,
                MAIN_FILE
            )

        val temp =
            File(
                project.directory,
                "$MAIN_FILE.tmp"
            )

        val emergencyOld =
            File(
                project.directory,
                "$MAIN_FILE.old"
            )

        try {

            temp.delete()

            temp.writeText(
                prepared,
                Charsets.UTF_8
            )

            val tempReadback =
                temp.readText(
                    Charsets.UTF_8
                )

            if (
                tempReadback.isBlank()
            ) {

                throw IllegalStateException(
                    "Temporary project file is empty."
                )
            }

            val problems =
                validateProject(
                    project,
                    tempReadback
                )

            val fatal =
                problems.any {
                    isFatalValidationProblem(it)
                }

            if (fatal) {

                throw IllegalStateException(
                    problems.joinToString("; ")
                )
            }

            /*
             * Only back up the current file AFTER the new temporary
             * file has passed validation.
             */
            if (
                target.exists() &&
                target.length() >
                    0L
            ) {

                createBackup(
                    project,
                    target
                )

                try {

                    target.copyTo(
                        emergencyOld,
                        overwrite =
                            true
                    )

                } catch (
                    _: Exception
                ) {
                    /*
                     * Timestamped backup already exists.
                     */
                }
            }

            /*
             * renameTo is normally atomic when source and destination
             * are on the same filesystem.
             */
            if (target.exists()) {
                target.delete()
            }

            val renamed =
                temp.renameTo(
                    target
                )

            if (!renamed) {

                temp.copyTo(
                    target,
                    overwrite =
                        true
                )

                temp.delete()
            }

            /*
             * Read the final destination again.
             * Never trust only the temporary validation.
             */
            val finalHtml =
                target.readText(
                    Charsets.UTF_8
                )

            val finalProblems =
                validateProject(
                    project,
                    finalHtml
                )

            val finalFatal =
                finalProblems.any {
                    isFatalValidationProblem(it)
                }

            if (finalFatal) {

                /*
                 * Emergency restore if the final replacement somehow
                 * became invalid during the filesystem operation.
                 */
                if (
                    emergencyOld.exists() &&
                    emergencyOld.length() >
                        0L
                ) {

                    emergencyOld.copyTo(
                        target,
                        overwrite =
                            true
                    )
                }

                throw IllegalStateException(
                    "Final saved project failed validation: " +
                        finalProblems.joinToString("; ")
                )
            }

            setCurrentProject(
                project
            )

            if (makePreviewable) {

                setPreviewProject(
                    project
                )
            }

            emergencyOld.delete()

            cleanOldBackups(
                project
            )

        } catch (
            e: Exception
        ) {

            temp.delete()

            /*
             * If the target vanished during replacement but an
             * emergency copy exists, restore it.
             */
            if (
                !target.exists() &&
                emergencyOld.exists()
            ) {

                try {

                    emergencyOld.copyTo(
                        target,
                        overwrite =
                            true
                    )

                } catch (
                    _: Exception
                ) {
                }
            }

            throw e
        }
    }

    // ============================================================
    // GENERATED HTML NORMALIZATION
    // ============================================================

    private fun prepareGeneratedHtml(
        project: JarvisProject,
        source: String
    ): String {

        var html =
            trimToHtmlDocument(
                source
            )
                .trim()

        if (html.isBlank()) {
            return html
        }

        /*
         * Exact fix for the failure:
         *
         * "Website is missing a mobile viewport."
         *
         * A viewport tag is deterministic HTML boilerplate. There is no
         * reason to run another LLM generation just to add it.
         */
        if (
            project.type ==
                JarvisProjectType.WEBSITE &&
            !hasViewportMeta(html)
        ) {

            html =
                injectViewportMeta(
                    html
                )
        }

        return html.trim()
    }

    private fun hasViewportMeta(
        html: String
    ): Boolean {

        val viewportRegex =
            Regex(
                """<meta\b[^>]*\bname\s*=\s*["']?viewport["']?[^>]*>""",
                setOf(
                    RegexOption.IGNORE_CASE,
                    RegexOption.DOT_MATCHES_ALL
                )
            )

        return viewportRegex.containsMatchIn(
            html
        )
    }

    private fun injectViewportMeta(
        html: String
    ): String {

        if (hasViewportMeta(html)) {
            return html
        }

        val viewport =
            """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""

        val headRegex =
            Regex(
                """<head\b[^>]*>""",
                RegexOption.IGNORE_CASE
            )

        val headMatch =
            headRegex.find(html)

        if (headMatch != null) {

            val position =
                headMatch.range.last + 1

            return buildString(
                html.length +
                    viewport.length +
                    2
            ) {

                append(
                    html.substring(
                        0,
                        position
                    )
                )

                append('\n')
                append(viewport)
                append('\n')

                append(
                    html.substring(
                        position
                    )
                )
            }
        }

        /*
         * If <html> exists but <head> does not, insert a minimal head.
         */
        val htmlRegex =
            Regex(
                """<html\b[^>]*>""",
                RegexOption.IGNORE_CASE
            )

        val htmlMatch =
            htmlRegex.find(html)

        if (htmlMatch != null) {

            val position =
                htmlMatch.range.last + 1

            val head =
                """
<head>
$viewport
</head>
""".trimIndent()

            return buildString(
                html.length +
                    head.length +
                    2
            ) {

                append(
                    html.substring(
                        0,
                        position
                    )
                )

                append('\n')
                append(head)
                append('\n')

                append(
                    html.substring(
                        position
                    )
                )
            }
        }

        /*
         * Leave malformed non-document output alone.
         * Validation will reject it instead of trying to guess.
         */
        return html
    }

    // ============================================================
    // MODEL OUTPUT EXTRACTION
    // ============================================================

    fun extractGeneratedHtml(
        rawOutput: String
    ): String? {

        if (rawOutput.isBlank()) {
            return null
        }

        var candidate =
            rawOutput.trim()

        /*
         * Preferred JARVIS format.
         */
        val tagged =
            Regex(
                """(?is)<JARVIS_FILE>\s*(.*?)\s*</JARVIS_FILE>"""
            )
                .find(candidate)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!tagged.isNullOrBlank()) {

            candidate =
                tagged
        }

        /*
         * Also accept normal markdown code fences because small models
         * sometimes ignore the exact output wrapper.
         */
        if (
            candidate.contains(
                "```"
            )
        ) {

            val fenced =
                Regex(
                    """(?is)```(?:html)?\s*(.*?)\s*```"""
                )
                    .find(candidate)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()

            if (!fenced.isNullOrBlank()) {

                candidate =
                    fenced
            }
        }

        candidate =
            trimToHtmlDocument(
                candidate
            )

        if (candidate.isBlank()) {
            return null
        }

        /*
         * Do not accept plain conversation as HTML.
         */
        if (
            !candidate.contains(
                "<html",
                ignoreCase =
                    true
            ) &&
            !candidate.contains(
                "<!doctype",
                ignoreCase =
                    true
            )
        ) {
            return null
        }

        return candidate.trim()
    }

    private fun trimToHtmlDocument(
        source: String
    ): String {

        var value =
            source.trim()

        if (value.isBlank()) {
            return value
        }

        /*
         * Remove anything the model says before the actual document.
         */
        val doctypeIndex =
            value.indexOf(
                "<!doctype",
                ignoreCase =
                    true
            )

        val htmlIndex =
            value.indexOf(
                "<html",
                ignoreCase =
                    true
            )

        val start =
            when {

                doctypeIndex >= 0 &&
                    htmlIndex >= 0 ->
                    minOf(
                        doctypeIndex,
                        htmlIndex
                    )

                doctypeIndex >= 0 ->
                    doctypeIndex

                htmlIndex >= 0 ->
                    htmlIndex

                else ->
                    0
            }

        if (start > 0) {

            value =
                value.substring(start)
        }

        /*
         * Most important extraction fix:
         * stop at the FIRST complete </html>.
         *
         * Small models often output:
         *
         * </html>
         * "Here is your finished website..."
         *
         * That chatter must never become part of index.html.
         */
        val closing =
            Regex(
                """</html\s*>""",
                RegexOption.IGNORE_CASE
            )
                .find(value)

        if (closing != null) {

            value =
                value.substring(
                    0,
                    closing.range.last + 1
                )
        }

        return value.trim()
    }

    // ============================================================
    // VALIDATION
    // ============================================================

    fun validateProject(
        project: JarvisProject,
        html: String
    ): List<String> {

        val problems =
            mutableListOf<String>()

        if (html.isBlank()) {

            problems +=
                "Project output is empty"

            return problems
        }

        if (
            html.length >
                MAX_HTML_SIZE
        ) {

            problems +=
                "Project output is too large"
        }

        if (
            !html.contains(
                "<html",
                ignoreCase =
                    true
            )
        ) {

            problems +=
                "Project is missing <html>"
        }

        if (
            !html.contains(
                "</html>",
                ignoreCase =
                    true
            )
        ) {

            problems +=
                "Project is missing </html>"
        }

        if (
            !html.contains(
                "<body",
                ignoreCase =
                    true
            )
        ) {

            problems +=
                "Project is missing <body>"
        }

        if (
            !html.contains(
                "</body>",
                ignoreCase =
                    true
            )
        ) {

            problems +=
                "Project is missing </body>"
        }

        when (project.type) {

            JarvisProjectType.WEBSITE -> {

                if (!hasViewportMeta(html)) {

                    problems +=
                        "Website is missing a mobile viewport"
                }
            }

            JarvisProjectType.GAME_2D -> {

                if (
                    !html.contains(
                        "<canvas",
                        ignoreCase =
                            true
                    )
                ) {

                    problems +=
                        "2D game is missing its canvas"
                }

                if (
                    !html.contains(
                        "<script",
                        ignoreCase =
                            true
                    )
                ) {

                    problems +=
                        "2D game is missing JavaScript"
                }

                if (
                    !html.contains(
                        "requestAnimationFrame",
                        ignoreCase =
                            true
                    )
                ) {

                    problems +=
                        "2D game is missing its animation loop"
                }
            }

            JarvisProjectType.GAME_3D -> {

                val hasThree =
                    html.contains(
                        "three.module",
                        ignoreCase =
                            true
                    ) ||
                    html.contains(
                        "from 'three'",
                        ignoreCase =
                            true
                    ) ||
                    html.contains(
                        "from \"three\"",
                        ignoreCase =
                            true
                    ) ||
                    html.contains(
                        "THREE.",
                        ignoreCase =
                            false
                    )

                if (!hasThree) {

                    problems +=
                        "3D game is missing Three.js"
                }

                if (
                    !html.contains(
                        "requestAnimationFrame",
                        ignoreCase =
                            true
                    )
                ) {

                    problems +=
                        "3D game is missing its animation loop"
                }
            }

            JarvisProjectType.UNKNOWN -> {

                problems +=
                    "Project type is unknown"
            }
        }

        return problems
    }

    fun isFatalValidationProblem(
        problem: String
    ): Boolean {

        /*
         * At the moment every validator result represents something
         * that would make Preview unreliable.
         *
         * Keeping this function separate lets us add warnings later
         * without changing MainActivity.
         */
        return problem.isNotBlank()
    }

    // ============================================================
    // BUILDER PROMPTS
    // ============================================================

    fun builderSystemPrompt(
        type: JarvisProjectType
    ): String {

        return when (type) {

            JarvisProjectType.WEBSITE ->

                """
You are JARVIS Builder running on Android.
Return one COMPLETE compact index.html only.
Wrap the file in <JARVIS_FILE> and </JARVIS_FILE>.
Always include:
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body>
...
</body>
</html>
Do not explain the code.
Do not use markdown unless unavoidable.
Use inline CSS and JavaScript.
Keep output small.
No base64 assets.
No huge SVG.
No frameworks.
Make it responsive and mobile friendly.
Never stop before </body> and </html>.
""".trimIndent()

            JarvisProjectType.GAME_2D ->

                """
You are JARVIS 2D Game Builder.
Modify the supplied working game while preserving its playable foundation.
Return one COMPLETE index.html inside <JARVIS_FILE> tags.
Keep the canvas.
Keep requestAnimationFrame.
Keep touch controls.
Keep keyboard controls.
Keep code compact.
No frameworks.
No base64.
No explanation.
Always finish </body> and </html>.
""".trimIndent()

            JarvisProjectType.GAME_3D ->

                """
You are JARVIS 3D Game Builder.
Modify the supplied working Three.js game while preserving its playable foundation.
Return one COMPLETE index.html inside <JARVIS_FILE> tags.
Keep Three.js.
Keep the renderer, scene, camera and animation loop.
Keep mobile touch controls.
Keep code compact.
No base64.
No explanation.
Always finish </body> and </html>.
""".trimIndent()

            JarvisProjectType.UNKNOWN ->

                """
You are JARVIS Builder.
Return one complete compact HTML project.
Do not explain the output.
""".trimIndent()
        }
    }

    fun createEditPrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val current =
            readMainFile(project)
                ?: workingFallback(
                    project.type
                )

        /*
         * The local 0.5B model has a limited context window.
         * Keep instructions short and let the existing working project
         * provide most of the context.
         */
        return """
EDIT REQUEST:
$userRequest

PROJECT TYPE:
${projectTypeName(project.type)}

RULES:
Keep the existing project working.
Preserve controls and required foundation code.
Make only the requested changes.
Return the complete replacement index.html.
Return only <JARVIS_FILE>...</JARVIS_FILE>.
Finish </body> and </html>.

CURRENT INDEX.HTML:
$current
""".trimIndent()
    }

    fun createInitialGamePrompt(
        project: JarvisProject,
        request: String
    ): String {

        return """
Project: ${project.name}
Type: ${projectTypeName(project.type)}
Request: $request

Use the working foundation.
Keep the result compact and playable.
""".trimIndent()
    }

    /*
     * Kept for future manual repair tools.
     *
     * MainActivity intentionally does NOT automatically run another
     * model pass when validation fails. Automatic second generations
     * increase memory pressure and were one of the behaviours we wanted
     * to avoid on the phone.
     */
    fun createRepairPrompt(
        project: JarvisProject,
        brokenHtml: String,
        problems: List<String>
    ): String {

        return """
Repair this ${projectTypeName(project.type)}.

Problems:
${problems.joinToString("\n")}

Return one COMPLETE replacement index.html.
Keep it compact.
Return only <JARVIS_FILE>...</JARVIS_FILE>.

BROKEN FILE:
$brokenHtml
""".trimIndent()
    }

    private fun workingFallback(
        type: JarvisProjectType
    ): String {

        return when (type) {

            JarvisProjectType.GAME_2D ->
                create2DGameFoundation()

            JarvisProjectType.GAME_3D ->
                create3DGameFoundation()

            JarvisProjectType.WEBSITE ->
                createWebsiteFoundation()

            JarvisProjectType.UNKNOWN ->
                createWebsiteFoundation()
        }
    }

    // ============================================================
    // WEBSITE FOUNDATION
    // ============================================================

    private fun createWebsiteFoundation():
        String {

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Jarvis Website</title>
<style>
*{box-sizing:border-box}
body{
margin:0;
font-family:Arial,sans-serif;
background:#07111c;
color:#eefaff;
min-height:100vh;
display:grid;
place-items:center;
padding:24px
}
main{
width:min(760px,100%);
background:#0d1d2b;
padding:28px;
border-radius:18px
}
h1{margin-top:0}
button{
border:0;
border-radius:12px;
padding:12px 18px;
font-weight:bold;
cursor:pointer
}
</style>
</head>
<body>
<main>
<h1>Jarvis Website</h1>
<p>Your website is ready to edit.</p>
<button onclick="alert('JARVIS ready')">Test</button>
</main>
</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // 2D GAME FOUNDATION
    // ============================================================

    private fun create2DGameFoundation():
        String {

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Jarvis 2D Game</title>
<style>
*{box-sizing:border-box}
html,body{
margin:0;
width:100%;
height:100%;
overflow:hidden;
background:#07111c;
font-family:Arial,sans-serif;
touch-action:none;
user-select:none
}
#game{
display:block;
width:100vw;
height:100vh;
background:linear-gradient(#12324b,#07111c)
}
#hud{
position:fixed;
top:12px;
left:12px;
z-index:5;
color:white;
background:#0008;
padding:9px 12px;
border-radius:12px;
font-weight:bold;
pointer-events:none
}
#controls{
position:fixed;
left:14px;
right:14px;
bottom:18px;
z-index:6;
display:flex;
justify-content:space-between;
pointer-events:none
}
.pad{
display:flex;
gap:8px;
pointer-events:auto
}
.ctrl{
width:64px;
height:64px;
border:0;
border-radius:50%;
font-size:26px;
font-weight:bold;
background:#ffffffd9;
color:#07111c
}
</style>
</head>
<body>

<canvas id="game"></canvas>
<div id="hud">Score: <span id="score">0</span></div>

<div id="controls">
<div class="pad">
<button class="ctrl" id="left">◀</button>
<button class="ctrl" id="right">▶</button>
</div>
<div class="pad">
<button class="ctrl" id="up">▲</button>
<button class="ctrl" id="down">▼</button>
</div>
</div>

<script>
const canvas=document.getElementById("game");
const ctx=canvas.getContext("2d");
const scoreEl=document.getElementById("score");

let W=0,H=0,score=0;

const keys={
left:false,
right:false,
up:false,
down:false
};

const player={
x:120,
y:120,
r:18,
speed:260
};

const coin={
x:300,
y:220,
r:12
};

function resize(){
const dpr=Math.min(window.devicePixelRatio||1,2);
W=window.innerWidth;
H=window.innerHeight;
canvas.width=Math.floor(W*dpr);
canvas.height=Math.floor(H*dpr);
canvas.style.width=W+"px";
canvas.style.height=H+"px";
ctx.setTransform(dpr,0,0,dpr,0,0);

player.x=Math.max(player.r,Math.min(W-player.r,player.x));
player.y=Math.max(player.r,Math.min(H-player.r,player.y));
}

window.addEventListener("resize",resize);
resize();

function randomCoin(){
coin.x=40+Math.random()*Math.max(40,W-80);
coin.y=70+Math.random()*Math.max(40,H-140);
}

function bindButton(id,key){
const el=document.getElementById(id);

const on=e=>{
e.preventDefault();
keys[key]=true;
};

const off=e=>{
e.preventDefault();
keys[key]=false;
};

el.addEventListener("pointerdown",on);
el.addEventListener("pointerup",off);
el.addEventListener("pointercancel",off);
el.addEventListener("pointerleave",off);
}

bindButton("left","left");
bindButton("right","right");
bindButton("up","up");
bindButton("down","down");

window.addEventListener("keydown",e=>{
if(e.key==="ArrowLeft"||e.key==="a")keys.left=true;
if(e.key==="ArrowRight"||e.key==="d")keys.right=true;
if(e.key==="ArrowUp"||e.key==="w")keys.up=true;
if(e.key==="ArrowDown"||e.key==="s")keys.down=true;
});

window.addEventListener("keyup",e=>{
if(e.key==="ArrowLeft"||e.key==="a")keys.left=false;
if(e.key==="ArrowRight"||e.key==="d")keys.right=false;
if(e.key==="ArrowUp"||e.key==="w")keys.up=false;
if(e.key==="ArrowDown"||e.key==="s")keys.down=false;
});

let last=performance.now();

function update(dt){
let dx=(keys.right?1:0)-(keys.left?1:0);
let dy=(keys.down?1:0)-(keys.up?1:0);

if(dx||dy){
const len=Math.hypot(dx,dy)||1;
dx/=len;
dy/=len;
player.x+=dx*player.speed*dt;
player.y+=dy*player.speed*dt;
}

player.x=Math.max(player.r,Math.min(W-player.r,player.x));
player.y=Math.max(player.r,Math.min(H-player.r,player.y));

if(
Math.hypot(
player.x-coin.x,
player.y-coin.y
)<player.r+coin.r
){
score++;
scoreEl.textContent=score;
randomCoin();
}
}

function draw(){
ctx.clearRect(0,0,W,H);

ctx.fillStyle="#12324b";
ctx.fillRect(0,0,W,H);

ctx.strokeStyle="#ffffff12";
ctx.lineWidth=1;

for(let x=0;x<W;x+=40){
ctx.beginPath();
ctx.moveTo(x,0);
ctx.lineTo(x,H);
ctx.stroke();
}

for(let y=0;y<H;y+=40){
ctx.beginPath();
ctx.moveTo(0,y);
ctx.lineTo(W,y);
ctx.stroke();
}

ctx.beginPath();
ctx.arc(coin.x,coin.y,coin.r,0,Math.PI*2);
ctx.fillStyle="#ffd84d";
ctx.fill();

ctx.beginPath();
ctx.arc(player.x,player.y,player.r,0,Math.PI*2);
ctx.fillStyle="#00d9ff";
ctx.fill();

ctx.beginPath();
ctx.arc(player.x-6,player.y-4,2.5,0,Math.PI*2);
ctx.arc(player.x+6,player.y-4,2.5,0,Math.PI*2);
ctx.fillStyle="#07111c";
ctx.fill();
}

function frame(now){
const dt=Math.min((now-last)/1000,0.033);
last=now;

update(dt);
draw();

requestAnimationFrame(frame);
}

randomCoin();
requestAnimationFrame(frame);
</script>
</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // 3D GAME FOUNDATION
    // ============================================================

    private fun create3DGameFoundation():
        String {

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Jarvis 3D Game</title>
<style>
*{box-sizing:border-box}
html,body{
margin:0;
width:100%;
height:100%;
overflow:hidden;
background:#07111c;
touch-action:none;
user-select:none;
font-family:Arial,sans-serif
}
canvas{display:block}
#hud{
position:fixed;
top:12px;
left:12px;
z-index:10;
color:#fff;
background:#0008;
padding:10px 12px;
border-radius:12px;
font-weight:bold;
pointer-events:none
}
#controls{
position:fixed;
left:14px;
right:14px;
bottom:18px;
z-index:10;
display:flex;
justify-content:space-between;
pointer-events:none
}
.pad{
display:flex;
gap:8px;
pointer-events:auto
}
.ctrl{
width:62px;
height:62px;
border:0;
border-radius:50%;
background:#ffffffd9;
color:#07111c;
font-size:25px;
font-weight:bold
}
</style>
</head>
<body>

<div id="hud">
JARVIS 3D • Target: <span id="distance">0</span>m
</div>

<div id="controls">
<div class="pad">
<button class="ctrl" id="left">◀</button>
<button class="ctrl" id="right">▶</button>
</div>
<div class="pad">
<button class="ctrl" id="forward">▲</button>
<button class="ctrl" id="back">▼</button>
</div>
</div>

<script type="module">
import * as THREE from "https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js";

const scene=new THREE.Scene();
scene.background=new THREE.Color(0x78b7df);
scene.fog=new THREE.Fog(0x78b7df,35,120);

const camera=new THREE.PerspectiveCamera(
65,
innerWidth/innerHeight,
0.1,
180
);

camera.position.set(0,5,8);

const renderer=new THREE.WebGLRenderer({
antialias:true,
powerPreference:"high-performance"
});

renderer.setPixelRatio(
Math.min(devicePixelRatio||1,1.5)
);

renderer.setSize(
innerWidth,
innerHeight
);

document.body.prepend(
renderer.domElement
);

scene.add(
new THREE.HemisphereLight(
0xffffff,
0x355030,
2.2
)
);

const sun=
new THREE.DirectionalLight(
0xffffff,
2.5
);

sun.position.set(
8,
14,
5
);

scene.add(sun);

const floor=
new THREE.Mesh(
new THREE.PlaneGeometry(
160,
160
),
new THREE.MeshStandardMaterial({
color:0x4c8750,
roughness:1
})
);

floor.rotation.x=
-Math.PI/2;

scene.add(floor);

const grid=
new THREE.GridHelper(
160,
80,
0xffffff,
0xffffff
);

grid.material.opacity=
0.12;

grid.material.transparent=
true;

scene.add(grid);

const player=
new THREE.Mesh(
new THREE.CapsuleGeometry(
0.55,
1.1,
6,
12
),
new THREE.MeshStandardMaterial({
color:0x00d9ff
})
);

player.position.y=
1.1;

scene.add(player);

const target=
new THREE.Mesh(
new THREE.SphereGeometry(
0.7,
20,
16
),
new THREE.MeshStandardMaterial({
color:0xffd84d,
emissive:0x553500
})
);

target.position.set(
8,
0.7,
-10
);

scene.add(target);

for(let i=0;i<28;i++){

const h=
1.5+
Math.random()*5;

const box=
new THREE.Mesh(
new THREE.BoxGeometry(
1.5+
Math.random()*2,
h,
1.5+
Math.random()*2
),
new THREE.MeshStandardMaterial({
color:
new THREE.Color().setHSL(
0.3+
Math.random()*0.15,
0.35,
0.3+
Math.random()*0.2
)
})
);

box.position.set(
(Math.random()-.5)*70,
h/2,
(Math.random()-.5)*70
);

if(
Math.abs(box.position.x)<5 &&
Math.abs(box.position.z)<8
){
box.position.x+=10;
}

scene.add(box);
}

const keys={
left:false,
right:false,
forward:false,
back:false
};

function bind(id,key){

const el=
document.getElementById(id);

const on=e=>{
e.preventDefault();
keys[key]=true;
};

const off=e=>{
e.preventDefault();
keys[key]=false;
};

el.addEventListener(
"pointerdown",
on
);

el.addEventListener(
"pointerup",
off
);

el.addEventListener(
"pointercancel",
off
);

el.addEventListener(
"pointerleave",
off
);
}

bind("left","left");
bind("right","right");
bind("forward","forward");
bind("back","back");

addEventListener(
"keydown",
e=>{

if(e.key==="a"||e.key==="ArrowLeft"){
keys.left=true;
}

if(e.key==="d"||e.key==="ArrowRight"){
keys.right=true;
}

if(e.key==="w"||e.key==="ArrowUp"){
keys.forward=true;
}

if(e.key==="s"||e.key==="ArrowDown"){
keys.back=true;
}
}
);

addEventListener(
"keyup",
e=>{

if(e.key==="a"||e.key==="ArrowLeft"){
keys.left=false;
}

if(e.key==="d"||e.key==="ArrowRight"){
keys.right=false;
}

if(e.key==="w"||e.key==="ArrowUp"){
keys.forward=false;
}

if(e.key==="s"||e.key==="ArrowDown"){
keys.back=false;
}
}
);

const distanceEl=
document.getElementById(
"distance"
);

const clock=
new THREE.Clock();

function update(){

const dt=
Math.min(
clock.getDelta(),
0.033
);

let x=
(keys.right?1:0)-
(keys.left?1:0);

let z=
(keys.back?1:0)-
(keys.forward?1:0);

if(x||z){

const length=
Math.hypot(
x,
z
)||1;

x/=length;
z/=length;

player.position.x+=
x*6*dt;

player.position.z+=
z*6*dt;
}

player.position.x=
THREE.MathUtils.clamp(
player.position.x,
-75,
75
);

player.position.z=
THREE.MathUtils.clamp(
player.position.z,
-75,
75
);

const desired=
new THREE.Vector3(
player.position.x,
5,
player.position.z+8
);

camera.position.lerp(
desired,
0.08
);

camera.lookAt(
player.position.x,
1,
player.position.z-3
);

const distance=
player.position.distanceTo(
target.position
);

distanceEl.textContent=
distance.toFixed(1);

target.rotation.y+=
dt;

target.position.y=
0.8+
Math.sin(
performance.now()*0.003
)*0.18;
}

function animate(){

requestAnimationFrame(
animate
);

update();

renderer.render(
scene,
camera
);
}

animate();

addEventListener(
"resize",
()=>{

camera.aspect=
innerWidth/
innerHeight;

camera.updateProjectionMatrix();

renderer.setSize(
innerWidth,
innerHeight
);
}
);
</script>
</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // PROJECT NAMES
    // ============================================================

    fun suggestedProjectName(
        request: String,
        type: JarvisProjectType
    ): String {

        val lower =
            request.lowercase(
                Locale.getDefault()
            )

        val default =
            defaultProjectName(type)

        val cleaned =
            request
                .replace(
                    Regex(
                        """(?i)\b(create|make|build|generate|start|new|another|please|me|a|an)\b"""
                    ),
                    " "
                )
                .replace(
                    Regex(
                        """(?i)\b(website|web\s*site|webpage|web\s*page|2d\s*game|3d\s*game|game|project)\b"""
                    ),
                    " "
                )
                .replace(
                    Regex(
                        """\s+"""
                    ),
                    " "
                )
                .trim()
                .take(45)

        if (
            cleaned.length <
                3 ||
            lower ==
                cleaned.lowercase(
                    Locale.getDefault()
                )
        ) {
            return default
        }

        return cleaned
            .split(" ")
            .filter {
                it.isNotBlank()
            }
            .joinToString(" ") {

                it.replaceFirstChar { char ->

                    if (
                        char.isLowerCase()
                    ) {

                        char.titlecase(
                            Locale.getDefault()
                        )

                    } else {

                        char.toString()
                    }
                }
            }
            .ifBlank {
                default
            }
    }

    private fun defaultProjectName(
        type: JarvisProjectType
    ): String {

        return when (type) {

            JarvisProjectType.WEBSITE ->
                "Jarvis Website"

            JarvisProjectType.GAME_2D ->
                "Jarvis 2D Game"

            JarvisProjectType.GAME_3D ->
                "Jarvis 3D Game"

            JarvisProjectType.UNKNOWN ->
                "Jarvis Project"
        }
    }

    private fun sanitizeProjectName(
        name: String
    ): String {

        val safe =
            name
                .lowercase(
                    Locale.getDefault()
                )
                .replace(
                    Regex(
                        """[^a-z0-9]+"""
                    ),
                    "-"
                )
                .trim('-')
                .take(36)

        return safe.ifBlank {
            "jarvis-project"
        }
    }

    private fun projectTypeName(
        type: JarvisProjectType
    ): String {

        return when (type) {

            JarvisProjectType.WEBSITE ->
                "WEBSITE"

            JarvisProjectType.GAME_2D ->
                "2D GAME"

            JarvisProjectType.GAME_3D ->
                "3D GAME"

            JarvisProjectType.UNKNOWN ->
                "PROJECT"
        }
    }

    // ============================================================
    // METADATA
    // ============================================================

    private fun writeMetadata(
        project: JarvisProject
    ) {

        val metadata =
            """
id=${project.id}
name=${escapeMetadata(project.name)}
type=${project.type.name}
""".trimIndent()

        File(
            project.directory,
            META_FILE
        ).writeText(
            metadata,
            Charsets.UTF_8
        )
    }

    private fun loadProject(
        id: String
    ): JarvisProject? {

        val directory =
            File(
                root,
                id
            )

        if (
            !directory.exists() ||
            !directory.isDirectory
        ) {
            return null
        }

        val metadataFile =
            File(
                directory,
                META_FILE
            )

        if (!metadataFile.exists()) {
            return null
        }

        return try {

            val values =
                mutableMapOf<String, String>()

            metadataFile
                .readLines(
                    Charsets.UTF_8
                )
                .forEach { line ->

                    val separator =
                        line.indexOf('=')

                    if (separator > 0) {

                        val key =
                            line
                                .substring(
                                    0,
                                    separator
                                )
                                .trim()

                        val value =
                            line
                                .substring(
                                    separator + 1
                                )

                        values[key] =
                            value
                    }
                }

            val name =
                unescapeMetadata(
                    values["name"]
                        ?: id
                )

            val type =
                try {

                    JarvisProjectType.valueOf(
                        values["type"]
                            ?: JarvisProjectType.UNKNOWN.name
                    )

                } catch (
                    _: Exception
                ) {

                    JarvisProjectType.UNKNOWN
                }

            JarvisProject(
                id =
                    values["id"]
                        ?: id,
                name =
                    name,
                type =
                    type,
                directory =
                    directory
            )

        } catch (
            _: Exception
        ) {

            null
        }
    }

    private fun escapeMetadata(
        value: String
    ): String {

        return value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\r",
                ""
            )
    }

    private fun unescapeMetadata(
        value: String
    ): String {

        return value
            .replace(
                "\\n",
                "\n"
            )
            .replace(
                "\\\\",
                "\\"
            )
    }

    // ============================================================
    // BACKUPS
    // ============================================================

    private fun createBackup(
        project: JarvisProject,
        source: File
    ) {

        val backupDirectory =
            File(
                project.directory,
                "backups"
            ).apply {
                mkdirs()
            }

        val backup =
            File(
                backupDirectory,
                "index-${System.currentTimeMillis()}.html"
            )

        source.copyTo(
            backup,
            overwrite =
                false
        )

        cleanOldBackups(
            project
        )
    }

    private fun cleanOldBackups(
        project: JarvisProject
    ) {

        val backupDirectory =
            File(
                project.directory,
                "backups"
            )

        if (!backupDirectory.exists()) {
            return
        }

        val backups =
            backupDirectory
                .listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension.equals(
                            "html",
                            ignoreCase =
                                true
                        )
                }
                ?.sortedByDescending {
                    it.lastModified()
                }
                .orEmpty()

        backups
            .drop(MAX_BACKUPS)
            .forEach {

                try {
                    it.delete()
                } catch (
                    _: Exception
                ) {
                }
            }
    }
}
