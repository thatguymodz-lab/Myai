package com.novajarvis.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        private const val PREFS_NAME = "jarvis_builder"
        private const val CURRENT_PROJECT_ID = "current_project_id"
        private const val CURRENT_PROJECT_TYPE = "current_project_type"
        private const val CURRENT_PROJECT_NAME = "current_project_name"

        private const val PROJECT_INFO_FILE = "jarvis.project"
        private const val INDEX_FILE = "index.html"
    }

    private val prefs by lazy {
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    private val projectsRoot: File by lazy {
        File(
            context.filesDir,
            "jarvis_projects"
        ).apply {
            mkdirs()
        }
    }

    // ============================================================
    // REQUEST ROUTING
    // ============================================================

    fun detectProjectType(
        request: String
    ): JarvisProjectType {

        val text = request
            .lowercase(Locale.getDefault())
            .trim()

        if (text.isBlank()) {
            return JarvisProjectType.UNKNOWN
        }

        val explicit3D = listOf(
            "3d game",
            "3-d game",
            "three dimensional game",
            "three-dimensional game",
            "webgl game",
            "three.js game",
            "threejs game"
        )

        val explicit2D = listOf(
            "2d game",
            "2-d game",
            "two dimensional game",
            "two-dimensional game",
            "canvas game",
            "platform game",
            "platformer",
            "top down game",
            "top-down game",
            "side scroller",
            "side-scroller"
        )

        val websiteWords = listOf(
            "website",
            "web site",
            "webpage",
            "web page",
            "landing page",
            "portfolio site",
            "business site",
            "shop site",
            "company site"
        )

        if (
            explicit3D.any {
                text.contains(it)
            }
        ) {
            return JarvisProjectType.GAME_3D
        }

        if (
            explicit2D.any {
                text.contains(it)
            }
        ) {
            return JarvisProjectType.GAME_2D
        }

        val mentionsGame =
            text.contains("game")

        val mentions3D =
            text.contains("3d") ||
                text.contains("3-d") ||
                text.contains("three.js") ||
                text.contains("threejs") ||
                text.contains("webgl")

        val mentions2D =
            text.contains("2d") ||
                text.contains("2-d") ||
                text.contains("canvas")

        if (
            mentionsGame &&
            mentions3D
        ) {
            return JarvisProjectType.GAME_3D
        }

        if (
            mentionsGame &&
            mentions2D
        ) {
            return JarvisProjectType.GAME_2D
        }

        if (mentionsGame) {

            /*
             * A generic "make a game" request is intentionally
             * UNKNOWN.
             *
             * Jarvis must ask whether the user wants a 2D or
             * 3D game instead of guessing.
             */
            return JarvisProjectType.UNKNOWN
        }

        if (
            websiteWords.any {
                text.contains(it)
            }
        ) {
            return JarvisProjectType.WEBSITE
        }

        return JarvisProjectType.UNKNOWN
    }

    fun isNewBuildRequest(
        request: String
    ): Boolean {

        val text =
            request.lowercase(
                Locale.getDefault()
            )

        val buildWords = listOf(
            "build",
            "make",
            "create",
            "develop",
            "code",
            "generate",
            "start"
        )

        val projectWords = listOf(
            "website",
            "web site",
            "webpage",
            "web page",
            "game",
            "2d",
            "3d",
            "three.js",
            "threejs",
            "webgl"
        )

        return buildWords.any {
            text.contains(it)
        } &&
            projectWords.any {
                text.contains(it)
            }
    }

    fun isProjectEditRequest(
        request: String
    ): Boolean {

        if (getCurrentProject() == null) {
            return false
        }

        val text =
            request.lowercase(
                Locale.getDefault()
            )

        val editWords = listOf(
            "change",
            "edit",
            "update",
            "improve",
            "fix",
            "add",
            "remove",
            "continue",
            "make it",
            "make the",
            "make this",
            "replace",
            "upgrade",
            "increase",
            "decrease",
            "bigger",
            "smaller",
            "faster",
            "slower"
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
            type != JarvisProjectType.UNKNOWN
        ) {
            "A project type must be selected."
        }

        val safeName =
            sanitizeProjectName(
                requestedName
            )

        val id =
            UUID.randomUUID()
                .toString()
                .take(8)

        val typeFolder =
            when (type) {

                JarvisProjectType.WEBSITE ->
                    "websites"

                JarvisProjectType.GAME_2D ->
                    "games_2d"

                JarvisProjectType.GAME_3D ->
                    "games_3d"

                JarvisProjectType.UNKNOWN ->
                    "unknown"
            }

        /*
         * Each project gets its own isolated directory.
         *
         * This prevents a website from overwriting a game
         * or one game from overwriting another.
         */
        val directory =
            File(
                projectsRoot,
                "$typeFolder/${safeName}_$id"
            )

        directory.mkdirs()

        File(
            directory,
            "backups"
        ).mkdirs()

        val project =
            JarvisProject(
                id = id,
                name = safeName,
                type = type,
                directory = directory
            )

        writeProjectInfo(project)

        setCurrentProject(project)

        return project
    }

    // ============================================================
    // CURRENT PROJECT
    // ============================================================

    fun setCurrentProject(
        project: JarvisProject
    ) {

        prefs.edit()
            .putString(
                CURRENT_PROJECT_ID,
                project.id
            )
            .putString(
                CURRENT_PROJECT_TYPE,
                project.type.name
            )
            .putString(
                CURRENT_PROJECT_NAME,
                project.name
            )
            .apply()
    }

    fun getCurrentProject():
        JarvisProject? {

        val id =
            prefs.getString(
                CURRENT_PROJECT_ID,
                null
            ) ?: return null

        val typeName =
            prefs.getString(
                CURRENT_PROJECT_TYPE,
                null
            ) ?: return null

        val name =
            prefs.getString(
                CURRENT_PROJECT_NAME,
                null
            ) ?: return null

        val type =
            try {
                JarvisProjectType.valueOf(
                    typeName
                )
            } catch (_: Exception) {
                return null
            }

        val project =
            findProject(
                id = id,
                type = type,
                name = name
            ) ?: return null

        return project
    }

    private fun findProject(
        id: String,
        type: JarvisProjectType,
        name: String
    ): JarvisProject? {

        val typeFolder =
            when (type) {

                JarvisProjectType.WEBSITE ->
                    "websites"

                JarvisProjectType.GAME_2D ->
                    "games_2d"

                JarvisProjectType.GAME_3D ->
                    "games_3d"

                JarvisProjectType.UNKNOWN ->
                    return null
            }

        val directory =
            File(
                projectsRoot,
                "$typeFolder/${name}_$id"
            )

        if (!directory.exists()) {
            return null
        }

        return JarvisProject(
            id = id,
            name = name,
            type = type,
            directory = directory
        )
    }

    // ============================================================
    // FILE STORAGE
    // ============================================================

    fun saveMainFile(
        project: JarvisProject,
        html: String
    ): File {

        if (html.isBlank()) {
            throw IllegalArgumentException(
                "Jarvis generated an empty project."
            )
        }

        /*
         * Back up the current version before replacing it.
         */
        backupCurrentVersion(project)

        val index =
            File(
                project.directory,
                INDEX_FILE
            )

        index.writeText(html)

        setCurrentProject(project)

        return index
    }

    fun readMainFile(
        project: JarvisProject
    ): String? {

        val index =
            File(
                project.directory,
                INDEX_FILE
            )

        if (!index.exists()) {
            return null
        }

        return try {
            index.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun currentMainFile():
        File? {

        val project =
            getCurrentProject()
                ?: return null

        val index =
            File(
                project.directory,
                INDEX_FILE
            )

        return index.takeIf {
            it.exists() &&
                it.length() > 0L
        }
    }

    // ============================================================
    // VERSION BACKUPS
    // ============================================================

    private fun backupCurrentVersion(
        project: JarvisProject
    ) {

        val current =
            File(
                project.directory,
                INDEX_FILE
            )

        if (
            !current.exists() ||
            current.length() == 0L
        ) {
            return
        }

        val backupDirectory =
            File(
                project.directory,
                "backups"
            ).apply {
                mkdirs()
            }

        val formatter =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            )

        val stamp =
            formatter.format(
                Date()
            )

        val backup =
            File(
                backupDirectory,
                "index_$stamp.html"
            )

        current.copyTo(
            backup,
            overwrite = false
        )

        trimOldBackups(
            backupDirectory
        )
    }

    private fun trimOldBackups(
        directory: File
    ) {

        val backups =
            directory
                .listFiles()
                ?.filter {
                    it.isFile &&
                        it.extension
                            .equals(
                                "html",
                                ignoreCase = true
                            )
                }
                ?.sortedByDescending {
                    it.lastModified()
                }
                ?: return

        /*
         * Keep the newest 10 automatic versions.
         */
        backups
            .drop(10)
            .forEach {
                try {
                    it.delete()
                } catch (_: Exception) {
                }
            }
    }

    // ============================================================
    // OUTPUT EXTRACTION
    // ============================================================

    fun extractGeneratedHtml(
        output: String
    ): String? {

        /*
         * Preferred format from Jarvis Builder:
         *
         * <JARVIS_FILE>
         * ...
         * </JARVIS_FILE>
         */
        val tagged =
            Regex(
                "(?s)<JARVIS_FILE>\\\\s*(.*?)\\\\s*</JARVIS_FILE>",
                RegexOption.IGNORE_CASE
            )
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!tagged.isNullOrBlank()) {
            return tagged
        }

        /*
         * Fallback for normal Markdown HTML blocks.
         */
        val fenced =
            Regex(
                "(?s)```(?:html)?\\\\s*(.*?)\\\\s*```",
                RegexOption.IGNORE_CASE
            )
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!fenced.isNullOrBlank()) {
            return fenced
        }

        /*
         * Final fallback if the model outputs raw HTML.
         */
        val doctype =
            output.indexOf(
                "<!doctype",
                ignoreCase = true
            )

        if (doctype >= 0) {
            return output
                .substring(doctype)
                .trim()
        }

        val html =
            output.indexOf(
                "<html",
                ignoreCase = true
            )

        if (html >= 0) {
            return output
                .substring(html)
                .trim()
        }

        return null
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

        val lower =
            html.lowercase(
                Locale.getDefault()
            )

        if (
            !lower.contains("<html") &&
            !lower.contains("<!doctype")
        ) {
            problems.add(
                "Missing HTML document structure."
            )
        }

        if (!lower.contains("<body")) {
            problems.add(
                "Missing BODY element."
            )
        }

        if (
            !lower.contains("</html>")
        ) {
            problems.add(
                "Missing closing HTML element."
            )
        }

        when (project.type) {

            JarvisProjectType.WEBSITE -> {

                if (
                    !lower.contains(
                        "<meta name=\"viewport\""
                    ) &&
                    !lower.contains(
                        "<meta name='viewport'"
                    )
                ) {
                    problems.add(
                        "Website is missing a mobile viewport."
                    )
                }
            }

            JarvisProjectType.GAME_2D -> {

                if (
                    !lower.contains("<canvas")
                ) {
                    problems.add(
                        "2D game has no Canvas."
                    )
                }

                if (
                    !lower.contains("<script")
                ) {
                    problems.add(
                        "2D game has no game script."
                    )
                }

                if (
                    !lower.contains(
                        "requestanimationframe"
                    )
                ) {
                    problems.add(
                        "2D game has no animation/game loop."
                    )
                }
            }

            JarvisProjectType.GAME_3D -> {

                val hasThree =
                    lower.contains(
                        "three.module"
                    ) ||
                        lower.contains(
                            "three.min"
                        ) ||
                        lower.contains(
                            "from 'three'"
                        ) ||
                        lower.contains(
                            "from \"three\""
                        )

                if (!hasThree) {
                    problems.add(
                        "3D game has no Three.js engine."
                    )
                }

                if (
                    !lower.contains(
                        "requestanimationframe"
                    )
                ) {
                    problems.add(
                        "3D game has no animation/game loop."
                    )
                }
            }

            JarvisProjectType.UNKNOWN -> {

                problems.add(
                    "Project type is unknown."
                )
            }
        }

        return problems
    }

    // ============================================================
    // BUILDER SYSTEM PROMPTS
    // ============================================================

    fun builderSystemPrompt(
        type: JarvisProjectType
    ): String {

        val shared =
            """
You are JARVIS BUILDER.

You create complete runnable web projects for the user.

IMPORTANT RULES:

1. Never confuse a WEBSITE with a GAME.
2. Never change the project type unless the user explicitly asks.
3. Return ONE complete runnable index.html file.
4. Put HTML, CSS and JavaScript into the same index.html.
5. Never use TODO placeholders.
6. Never write "rest of code here".
7. Never replace code with ellipses.
8. The result must work on Android/mobile screens.
9. Preserve existing functionality when editing a project.
10. Finish the implementation instead of only explaining how to do it.
11. Fix obvious errors before returning the project.
12. Keep controls visible and usable on touchscreens.
13. Return the finished file between exactly these markers:

<JARVIS_FILE>
FULL FILE HERE
</JARVIS_FILE>

Do not put explanations inside the JARVIS_FILE markers.
""".trimIndent()

        val specialist =
            when (type) {

                JarvisProjectType.WEBSITE ->

                    """
PROJECT TYPE: WEBSITE

You are in WEBSITE BUILDER mode.

Build a responsive, polished website.

Use:
- semantic HTML
- responsive CSS
- JavaScript where useful
- mobile navigation where appropriate
- accessible buttons and controls
- professional layouts
- touch friendly interaction

Do NOT turn this project into a game unless the user explicitly requests a new game project.
""".trimIndent()

                JarvisProjectType.GAME_2D ->

                    """
PROJECT TYPE: 2D WEB GAME

You are in 2D GAME BUILDER mode.

Build an actual playable 2D browser game.

Use:
- HTML5 Canvas
- JavaScript
- requestAnimationFrame
- keyboard controls
- Android/mobile touch controls
- gameplay state
- collision detection where required
- scoring/progression where appropriate
- restart/game-over handling
- responsive canvas sizing
- visible HUD where appropriate

The result must be playable, not merely an animation.

Do NOT convert it into a website or 3D game unless the user explicitly requests a new project.
""".trimIndent()

                JarvisProjectType.GAME_3D ->

                    """
PROJECT TYPE: 3D WEB GAME

You are in 3D GAME BUILDER mode.

Build an actual playable 3D browser game.

Use Three.js as the 3D rendering engine.

Preferred import:

https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js

Include:
- scene
- camera
- renderer
- lighting
- game loop
- responsive resizing
- gameplay state
- Android/mobile touch controls
- keyboard controls where useful
- camera behaviour
- collision/gameplay logic where required
- HUD
- restart/game-over handling where appropriate

The result must be playable, not merely a 3D scene.

Do NOT convert it into a website or 2D game unless the user explicitly requests a new project.
""".trimIndent()

                JarvisProjectType.UNKNOWN ->

                    """
PROJECT TYPE: NOT SELECTED

Do not build anything yet.

Ask the user whether they want:

WEBSITE
2D GAME
or
3D GAME
""".trimIndent()
            }

        return """
$shared

$specialist
""".trimIndent()
    }

    // ============================================================
    // EDIT / CONTINUE PROMPT
    // ============================================================

    fun createEditPrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val existing =
            readMainFile(project)
                .orEmpty()

        return """
You are modifying an EXISTING Jarvis project.

PROJECT NAME:
${project.name}

PROJECT TYPE:
${project.type.name}

USER REQUEST:
$userRequest

IMPORTANT:
Keep this project as ${project.type.name}.
Do not accidentally convert it into another project type.
Preserve features that the user did not ask you to remove.
Return the COMPLETE updated index.html, not a patch.

CURRENT PROJECT FILE:

<JARVIS_EXISTING_FILE>
$existing
</JARVIS_EXISTING_FILE>
""".trimIndent()
    }

    // ============================================================
    // PROJECT INFORMATION
    // ============================================================

    private fun writeProjectInfo(
        project: JarvisProject
    ) {

        val info =
            """
id=${project.id}
name=${project.name}
type=${project.type.name}
created=${System.currentTimeMillis()}
""".trimIndent()

        File(
            project.directory,
            PROJECT_INFO_FILE
        ).writeText(info)
    }

    // ============================================================
    // PROJECT NAME
    // ============================================================

    fun suggestedProjectName(
        request: String,
        type: JarvisProjectType
    ): String {

        var cleaned =
            request
                .lowercase(
                    Locale.getDefault()
                )

        val removable =
            listOf(
                "please",
                "jarvis",
                "can you",
                "could you",
                "build me",
                "make me",
                "create me",
                "build",
                "make",
                "create",
                "a website",
                "website",
                "web site",
                "webpage",
                "web page",
                "a 2d game",
                "2d game",
                "a 3d game",
                "3d game",
                "web game",
                "game"
            )

        removable.forEach {
            cleaned =
                cleaned.replace(
                    it,
                    " "
                )
        }

        cleaned =
            cleaned
                .replace(
                    Regex("[^a-z0-9 ]"),
                    " "
                )
                .replace(
                    Regex("\\\\s+"),
                    " "
                )
                .trim()

        val words =
            cleaned
                .split(" ")
                .filter {
                    it.isNotBlank()
                }
                .take(5)

        if (words.isNotEmpty()) {

            return sanitizeProjectName(
                words.joinToString("_")
            )
        }

        return when (type) {

            JarvisProjectType.WEBSITE ->
                "website"

            JarvisProjectType.GAME_2D ->
                "2d_game"

            JarvisProjectType.GAME_3D ->
                "3d_game"

            JarvisProjectType.UNKNOWN ->
                "project"
        }
    }

    private fun sanitizeProjectName(
        name: String
    ): String {

        val cleaned =
            name
                .lowercase(
                    Locale.getDefault()
                )
                .replace(
                    Regex("[^a-z0-9_-]"),
                    "_"
                )
                .replace(
                    Regex("_+"),
                    "_"
                )
                .trim('_')
                .take(50)

        return cleaned.ifBlank {
            "jarvis_project"
        }
    }
}
