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

    private val projectsRoot by lazy {
        File(
            context.filesDir,
            "jarvis_projects"
        ).apply {
            mkdirs()
        }
    }

    // ============================================================
    // PROJECT TYPE DETECTION
    // ============================================================

    fun detectProjectType(
        request: String
    ): JarvisProjectType {

        val text =
            request
                .lowercase(Locale.getDefault())
                .trim()

        if (text.isBlank()) {
            return JarvisProjectType.UNKNOWN
        }

        val explicit3D =
            listOf(
                "3d game",
                "3-d game",
                "three dimensional game",
                "three-dimensional game",
                "webgl game",
                "three.js game",
                "threejs game"
            )

        val explicit2D =
            listOf(
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

        val websiteWords =
            listOf(
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

        /*
         * Generic "game" is deliberately UNKNOWN.
         * Jarvis must ask whether the user wants 2D or 3D.
         */
        if (mentionsGame) {
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

    // ============================================================
    // NEW PROJECT VS EDIT ROUTING
    // ============================================================

    fun isNewBuildRequest(
        request: String
    ): Boolean {

        val text =
            request
                .lowercase(Locale.getDefault())
                .trim()

        /*
         * These phrases strongly indicate the user is editing
         * the current project, even if they mention "website"
         * or "game".
         */
        val editIndicators =
            listOf(
                "current website",
                "current web site",
                "current webpage",
                "current web page",
                "current game",
                "current project",
                "existing website",
                "existing game",
                "existing project",
                "this website",
                "this web site",
                "this game",
                "this project",
                "my website",
                "my game",
                "my project",
                "improve",
                "update",
                "edit",
                "modify",
                "fix",
                "continue",
                "upgrade",
                "change",
                "add to",
                "remove from"
            )

        if (
            getCurrentProject() != null &&
            editIndicators.any {
                text.contains(it)
            }
        ) {
            return false
        }

        /*
         * When a project already exists, Jarvis should only
         * create another project when the user clearly says
         * they want a NEW one.
         */
        if (getCurrentProject() != null) {

            val explicitNewPhrases =
                listOf(
                    "create a new",
                    "create another",
                    "build a new",
                    "build another",
                    "make a new",
                    "make another",
                    "start a new",
                    "start another",
                    "new website",
                    "new web site",
                    "new 2d game",
                    "new 3d game",
                    "another website",
                    "another 2d game",
                    "another 3d game",
                    "separate website",
                    "separate game",
                    "separate project"
                )

            return explicitNewPhrases.any {
                text.contains(it)
            }
        }

        /*
         * If no project exists yet, normal creation language
         * is enough to start the first project.
         */
        val creationWords =
            listOf(
                "build",
                "create",
                "make",
                "develop",
                "generate",
                "start"
            )

        val projectWords =
            listOf(
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

        return creationWords.any {
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
            request
                .lowercase(Locale.getDefault())
                .trim()

        /*
         * Explicit NEW project language always wins.
         */
        val explicitNew =
            listOf(
                "create a new",
                "create another",
                "build a new",
                "build another",
                "make a new",
                "make another",
                "start a new",
                "start another",
                "new website",
                "new 2d game",
                "new 3d game",
                "another website",
                "another 2d game",
                "another 3d game",
                "separate project"
            )

        if (
            explicitNew.any {
                text.contains(it)
            }
        ) {
            return false
        }

        val editWords =
            listOf(
                "change",
                "edit",
                "update",
                "improve",
                "fix",
                "add",
                "remove",
                "continue",
                "upgrade",
                "replace",
                "increase",
                "decrease",
                "bigger",
                "smaller",
                "faster",
                "slower",
                "better",
                "more",
                "less",
                "make it",
                "make the",
                "make this",
                "give it",
                "put",
                "move",
                "resize",
                "rework",
                "redesign"
            )

        if (
            editWords.any {
                text.contains(it)
            }
        ) {
            return true
        }

        val currentReferences =
            listOf(
                "current project",
                "current website",
                "current game",
                "this project",
                "this website",
                "this game",
                "my project",
                "my website",
                "my game"
            )

        return currentReferences.any {
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

        writeProjectInfo(
            project
        )

        setCurrentProject(
            project
        )

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

        return findProject(
            id,
            type,
            name
        )
    }

    private fun findProject(
        id: String,
        type: JarvisProjectType,
        name: String
    ): JarvisProject? {

        val folder =
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
                "$folder/${name}_$id"
            )

        if (!directory.exists()) {
            return null
        }

        return JarvisProject(
            id,
            name,
            type,
            directory
        )
    }

    // ============================================================
    // PROJECT FILES
    // ============================================================

    fun saveMainFile(
        project: JarvisProject,
        html: String
    ): File {

        require(
            html.isNotBlank()
        ) {
            "Jarvis generated an empty project."
        }

        backupCurrentVersion(
            project
        )

        val index =
            File(
                project.directory,
                INDEX_FILE
            )

        index.writeText(
            html
        )

        setCurrentProject(
            project
        )

        return index
    }

    fun readMainFile(
        project: JarvisProject
    ): String? {

        val file =
            File(
                project.directory,
                INDEX_FILE
            )

        if (!file.exists()) {
            return null
        }

        return try {

            file.readText()

        } catch (_: Exception) {

            null
        }
    }

    fun currentMainFile():
        File? {

        val project =
            getCurrentProject()
                ?: return null

        return File(
            project.directory,
            INDEX_FILE
        ).takeIf {

            it.exists() &&
                it.length() > 0L
        }
    }

    // ============================================================
    // BACKUPS
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

        val backup =
            File(
                backupDirectory,
                "index_${formatter.format(Date())}.html"
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
                        it.extension.equals(
                            "html",
                            true
                        )
                }
                ?.sortedByDescending {
                    it.lastModified()
                }
                ?: return

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
    // AI OUTPUT EXTRACTION
    // ============================================================

    fun extractGeneratedHtml(
        output: String
    ): String? {

        val tagged =
            Regex(
                "(?s)<JARVIS_FILE>\\s*(.*?)\\s*</JARVIS_FILE>",
                RegexOption.IGNORE_CASE
            )
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!tagged.isNullOrBlank()) {
            return tagged
        }

        val fenced =
            Regex(
                "(?s)```(?:html)?\\s*(.*?)\\s*```",
                RegexOption.IGNORE_CASE
            )
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        if (!fenced.isNullOrBlank()) {
            return fenced
        }

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

        if (!lower.contains("</html>")) {

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

                if (!lower.contains("<canvas")) {

                    problems.add(
                        "2D game has no Canvas."
                    )
                }

                if (!lower.contains("<script")) {

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

Your job is to CREATE AND EDIT complete runnable projects.

CRITICAL RULES:

1. WEBSITE, 2D GAME and 3D GAME are separate project types.
2. Never silently convert one project type into another.
3. When editing, preserve the existing project.
4. Implement the user's requested changes into the existing code.
5. Return ONE COMPLETE index.html file.
6. Include all required HTML, CSS and JavaScript.
7. Never return partial code.
8. Never use TODO.
9. Never write "rest of code here".
10. Never replace sections with ellipses.
11. Never merely describe what should be built.
12. Build it.
13. Make the result responsive.
14. Make controls touch friendly.
15. Check the code for obvious syntax mistakes.
16. Do not invent local files or image paths that do not exist.
17. Avoid broken image URLs.
18. Prefer CSS artwork, gradients, shapes, emoji or inline SVG when artwork is needed and no real asset has been supplied.
19. Do not depend on random stock-image URLs.
20. Keep the project usable if optional visual resources fail.
21. Return the finished file exactly between:

<JARVIS_FILE>
FULL COMPLETE INDEX.HTML
</JARVIS_FILE>

Do not put explanations inside JARVIS_FILE.
""".trimIndent()

        val specialist =
            when (type) {

                JarvisProjectType.WEBSITE ->

                    """
PROJECT TYPE: WEBSITE

You are in WEBSITE BUILDER MODE.

Build a polished responsive WEBSITE.

The finished page should look intentionally designed rather than like raw browser HTML.

REQUIREMENTS:

- Include <!DOCTYPE html>.
- Include a mobile viewport meta tag.
- Include complete CSS.
- Use a coherent visual theme.
- Use spacing, cards, typography and layout deliberately.
- Style buttons and navigation.
- Use responsive layouts.
- Make it look good on an Android phone.
- Use semantic HTML.
- Add useful JavaScript interaction when appropriate.
- Use GBP (£) when the user is clearly asking for UK-style prices.
- Do not output broken placeholder images.
- If no image assets are available, create attractive visual sections using CSS, gradients, emoji or inline SVG instead.
- Do not use an external image simply to fill empty space.
- Make buttons visibly styled, not default browser links.
- Do not turn the website into a game.

Return a complete finished website.
""".trimIndent()

                JarvisProjectType.GAME_2D ->

                    """
PROJECT TYPE: 2D GAME

You are in 2D GAME BUILDER MODE.

Build a PLAYABLE browser game using HTML5 Canvas.

REQUIREMENTS:

- HTML5 Canvas.
- JavaScript game logic.
- requestAnimationFrame.
- responsive canvas.
- keyboard controls.
- Android touch controls.
- player movement.
- gameplay objective.
- collision logic where appropriate.
- scoring or progression where appropriate.
- HUD.
- restart handling.
- game-over or win handling where appropriate.
- prevent touch controls from scrolling the page during gameplay.
- use generated Canvas graphics rather than broken image assets.
- keep gameplay code self contained.
- do not merely create an animation.
- do not turn the project into a normal website.
- do not turn it into a 3D game.

Return a complete playable 2D game.
""".trimIndent()

                JarvisProjectType.GAME_3D ->

                    """
PROJECT TYPE: 3D GAME

You are in 3D GAME BUILDER MODE.

Build a PLAYABLE 3D browser game using Three.js.

Use:

https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js

REQUIREMENTS:

- Three.js scene.
- perspective camera.
- WebGL renderer.
- lighting.
- visible 3D player/world.
- requestAnimationFrame.
- responsive resizing.
- Android touch controls.
- keyboard controls where appropriate.
- player movement.
- camera behaviour.
- gameplay objective.
- collision/gameplay logic where appropriate.
- HUD.
- restart/game-over handling where appropriate.
- simple generated geometry/materials instead of nonexistent asset files.
- do not reference local textures/models that do not exist.
- keep the first version reasonably lightweight for a phone.
- do not merely make a static 3D scene.
- do not turn it into a website or 2D game.

Return a complete playable 3D game.
""".trimIndent()

                JarvisProjectType.UNKNOWN ->

                    """
PROJECT TYPE NOT SELECTED.

Do not guess.

Ask:

Would you like me to build that as a WEBSITE, 2D GAME, or 3D GAME?
""".trimIndent()
            }

        return """
$shared

$specialist
""".trimIndent()
    }

    // ============================================================
    // EDIT EXISTING PROJECT
    // ============================================================

    fun createEditPrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val existing =
            readMainFile(
                project
            ).orEmpty()

        return """
You are editing the user's CURRENT EXISTING PROJECT.

PROJECT NAME:
${project.name}

PROJECT ID:
${project.id}

LOCKED PROJECT TYPE:
${project.type.name}

USER'S REQUEST:
$userRequest

IMPORTANT EDITING RULES:

- This is NOT a new project.
- Modify the existing project below.
- Keep project ID ${project.id}.
- Keep project type ${project.type.name}.
- Preserve features the user did not ask to remove.
- Preserve working code wherever possible.
- Implement the requested improvements.
- Fix obvious errors you notice.
- Do not replace a detailed project with a simpler unrelated project.
- Do not reset the design unless the user asks.
- Do not invent missing local image files.
- Avoid broken external image URLs.
- Return the COMPLETE updated index.html.
- Never return only the changed section.
- Never return a patch.

EXISTING PROJECT:

<JARVIS_EXISTING_FILE>
$existing
</JARVIS_EXISTING_FILE>

Return the complete updated project inside JARVIS_FILE.
""".trimIndent()
    }

    // ============================================================
    // PROJECT INFO
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
        ).writeText(
            info
        )
    }

    // ============================================================
    // PROJECT NAMES
    // ============================================================

    fun suggestedProjectName(
        request: String,
        type: JarvisProjectType
    ): String {

        var cleaned =
            request.lowercase(
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
                "new",
                "another",
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
                    Regex("\\s+"),
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
