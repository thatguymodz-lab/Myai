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

        private const val PREVIEW_PROJECT_ID = "preview_project_id"
        private const val PREVIEW_PROJECT_TYPE = "preview_project_type"
        private const val PREVIEW_PROJECT_NAME = "preview_project_name"

        private const val BUILD_IN_PROGRESS = "build_in_progress"
        private const val BUILD_PROJECT_ID = "build_project_id"
        private const val BUILD_PROJECT_TYPE = "build_project_type"
        private const val BUILD_PROJECT_NAME = "build_project_name"

        private const val PROJECT_INFO_FILE = "jarvis.project"
        private const val INDEX_FILE = "index.html"
        private const val TEMP_INDEX_FILE = "index.html.tmp"
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
    // PROJECT TYPE
    // ============================================================

    fun detectProjectType(
        request: String
    ): JarvisProjectType {

        val text =
            request
                .lowercase(Locale.getDefault())
                .trim()

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
                "platformer",
                "platform game",
                "top down game",
                "top-down game",
                "side scroller",
                "side-scroller"
            )

        val websites =
            listOf(
                "website",
                "web site",
                "webpage",
                "web page",
                "landing page",
                "portfolio site",
                "business site"
            )

        val has3D =
            explicit3D.any {
                text.contains(it)
            }

        val has2D =
            explicit2D.any {
                text.contains(it)
            }

        val hasWebsite =
            websites.any {
                text.contains(it)
            }

        /*
         * If a prompt contains several project types, don't guess.
         *
         * This prevents source-code repair instructions mentioning
         * WEBSITE + 2D + 3D from accidentally starting a 3D game.
         */
        val explicitCount =
            listOf(
                has3D,
                has2D,
                hasWebsite
            ).count {
                it
            }

        if (explicitCount > 1) {
            return JarvisProjectType.UNKNOWN
        }

        if (has3D) {
            return JarvisProjectType.GAME_3D
        }

        if (has2D) {
            return JarvisProjectType.GAME_2D
        }

        if (hasWebsite) {
            return JarvisProjectType.WEBSITE
        }

        /*
         * A request that only says "game" is ambiguous.
         * JARVIS should ask 2D or 3D instead of guessing.
         */
        if (text.contains("game")) {
            return JarvisProjectType.UNKNOWN
        }

        return JarvisProjectType.UNKNOWN
    }

    // ============================================================
    // ROUTING
    // ============================================================

    fun isNewBuildRequest(
        request: String
    ): Boolean {

        val text =
            request
                .lowercase(Locale.getDefault())
                .trim()

        val current =
            getCurrentProject()

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
                "new web site",
                "new webpage",
                "new web page",
                "new landing page",
                "new 2d game",
                "new 3d game",
                "another website",
                "another web page",
                "another 2d game",
                "another 3d game",
                "separate website",
                "separate game",
                "separate project"
            )

        if (
            explicitNew.any {
                text.contains(it)
            }
        ) {
            return true
        }

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
            current != null &&
            editIndicators.any {
                text.contains(it)
            }
        ) {
            return false
        }

        if (current != null) {
            return false
        }

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
                "landing page",
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
                "new web site",
                "new webpage",
                "new web page",
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

        return listOf(
            "current project",
            "current website",
            "current game",
            "this project",
            "this website",
            "this game",
            "my project",
            "my website",
            "my game"
        ).any {
            text.contains(it)
        }
    }

    // ============================================================
    // CREATE PROJECT
    // ============================================================

    fun createProject(
        requestedName: String,
        type: JarvisProjectType
    ): JarvisProject {

        require(
            type != JarvisProjectType.UNKNOWN
        )

        val safeName =
            sanitizeProjectName(
                requestedName
            )

        val id =
            UUID
                .randomUUID()
                .toString()
                .take(8)

        val directory =
            File(
                projectsRoot,
                "${typeFolder(type)}/${safeName}_$id"
            )

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create project directory"
            )
        }

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

        /*
         * Games have a safe instant foundation.
         *
         * Websites intentionally do not get index.html until
         * generation has completed and validation has passed.
         */
        when (type) {

            JarvisProjectType.GAME_2D -> {
                saveMainFileAtomic(
                    project = project,
                    html = create2DGameFoundation(
                        displayName(
                            safeName
                        )
                    ),
                    makePreviewable = true
                )
            }

            JarvisProjectType.GAME_3D -> {
                saveMainFileAtomic(
                    project = project,
                    html = create3DGameFoundation(
                        displayName(
                            safeName
                        )
                    ),
                    makePreviewable = true
                )
            }

            JarvisProjectType.WEBSITE -> Unit

            JarvisProjectType.UNKNOWN -> Unit
        }

        return project
    }

    // ============================================================
    // CURRENT PROJECT
    // ============================================================

    fun setCurrentProject(
        project: JarvisProject
    ) {

        prefs
            .edit()
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

        return projectFromPreferences(
            CURRENT_PROJECT_ID,
            CURRENT_PROJECT_TYPE,
            CURRENT_PROJECT_NAME
        )
    }

    // ============================================================
    // VERIFIED PREVIEW
    // ============================================================

    fun setPreviewProject(
        project: JarvisProject
    ) {

        val html =
            readMainFile(
                project
            ) ?: return

        if (html.isBlank()) {
            return
        }

        val fatal =
            validateProject(
                project,
                html
            ).any {
                isFatalValidationProblem(
                    it
                )
            }

        if (fatal) {
            return
        }

        prefs
            .edit()
            .putString(
                PREVIEW_PROJECT_ID,
                project.id
            )
            .putString(
                PREVIEW_PROJECT_TYPE,
                project.type.name
            )
            .putString(
                PREVIEW_PROJECT_NAME,
                project.name
            )
            .apply()
    }

    fun getPreviewProject():
        JarvisProject? {

        val project =
            projectFromPreferences(
                PREVIEW_PROJECT_ID,
                PREVIEW_PROJECT_TYPE,
                PREVIEW_PROJECT_NAME
            ) ?: return null

        val html =
            readMainFile(
                project
            ) ?: return null

        if (html.isBlank()) {
            return null
        }

        val fatal =
            validateProject(
                project,
                html
            ).any {
                isFatalValidationProblem(
                    it
                )
            }

        return if (fatal) {
            null
        } else {
            project
        }
    }

    fun markProjectVerified(
        project: JarvisProject
    ): Boolean {

        val html =
            readMainFile(
                project
            ) ?: return false

        val fatal =
            validateProject(
                project,
                html
            ).any {
                isFatalValidationProblem(
                    it
                )
            }

        if (fatal) {
            return false
        }

        setCurrentProject(
            project
        )

        setPreviewProject(
            project
        )

        return true
    }

    // ============================================================
    // BUILD / CRASH RECOVERY
    // ============================================================

    fun beginBuild(
        project: JarvisProject
    ) {

        /*
         * commit() is intentional.
         *
         * The marker reaches disk before local AI inference starts.
         */
        prefs
            .edit()
            .putBoolean(
                BUILD_IN_PROGRESS,
                true
            )
            .putString(
                BUILD_PROJECT_ID,
                project.id
            )
            .putString(
                BUILD_PROJECT_TYPE,
                project.type.name
            )
            .putString(
                BUILD_PROJECT_NAME,
                project.name
            )
            .commit()
    }

    fun finishBuild(
        project: JarvisProject,
        successful: Boolean
    ) {

        if (successful) {
            markProjectVerified(
                project
            )
        }

        clearInterruptedBuild()
    }

    fun wasBuildInterrupted():
        Boolean {

        return prefs.getBoolean(
            BUILD_IN_PROGRESS,
            false
        )
    }

    fun getInterruptedProject():
        JarvisProject? {

        if (!wasBuildInterrupted()) {
            return null
        }

        return projectFromPreferences(
            BUILD_PROJECT_ID,
            BUILD_PROJECT_TYPE,
            BUILD_PROJECT_NAME
        )
    }

    fun clearInterruptedBuild() {

        prefs
            .edit()
            .putBoolean(
                BUILD_IN_PROGRESS,
                false
            )
            .remove(
                BUILD_PROJECT_ID
            )
            .remove(
                BUILD_PROJECT_TYPE
            )
            .remove(
                BUILD_PROJECT_NAME
            )
            .commit()
    }

    // ============================================================
    // FIND PROJECT
    // ============================================================

    private fun projectFromPreferences(
        idKey: String,
        typeKey: String,
        nameKey: String
    ): JarvisProject? {

        val id =
            prefs.getString(
                idKey,
                null
            ) ?: return null

        val typeName =
            prefs.getString(
                typeKey,
                null
            ) ?: return null

        val name =
            prefs.getString(
                nameKey,
                null
            ) ?: return null

        val type =
            try {
                JarvisProjectType.valueOf(
                    typeName
                )
            } catch (
                _: Exception
            ) {
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

        if (
            type ==
            JarvisProjectType.UNKNOWN
        ) {
            return null
        }

        val directory =
            File(
                projectsRoot,
                "${typeFolder(type)}/${name}_$id"
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

    private fun typeFolder(
        type: JarvisProjectType
    ): String {

        return when (type) {

            JarvisProjectType.WEBSITE ->
                "websites"

            JarvisProjectType.GAME_2D ->
                "games_2d"

            JarvisProjectType.GAME_3D ->
                "games_3d"

            JarvisProjectType.UNKNOWN ->
                "unknown"
        }
    }

    // ============================================================
    // SAFE FILE SAVING
    // ============================================================

    fun saveMainFile(
        project: JarvisProject,
        html: String
    ): File {

        return saveMainFileAtomic(
            project = project,
            html = html,
            makePreviewable = true
        )
    }

    fun saveMainFileAtomic(
        project: JarvisProject,
        html: String,
        makePreviewable: Boolean
    ): File {

        require(
            html.isNotBlank()
        )

        project.directory.mkdirs()

        val index =
            File(
                project.directory,
                INDEX_FILE
            )

        val temp =
            File(
                project.directory,
                TEMP_INDEX_FILE
            )

        backupCurrentVersion(
            project
        )

        try {

            temp.writeText(
                html
            )

            if (
                !temp.exists() ||
                temp.length() <= 0L
            ) {
                throw IllegalStateException(
                    "Temporary project file was not written"
                )
            }

            val written =
                temp.readText()

            val problems =
                validateProject(
                    project,
                    written
                )

            val fatal =
                problems.any {
                    isFatalValidationProblem(
                        it
                    )
                }

            if (fatal) {
                throw IllegalStateException(
                    "Project failed validation: ${
                        problems.joinToString(
                            "; "
                        )
                    }"
                )
            }

            /*
             * Keep another emergency copy of the previous file.
             */
            if (index.exists()) {

                try {

                    val old =
                        File(
                            project.directory,
                            "index.html.old"
                        )

                    if (old.exists()) {
                        old.delete()
                    }

                    index.copyTo(
                        old,
                        overwrite = true
                    )

                } catch (
                    _: Exception
                ) {
                    // Backup failure must not destroy a valid new build.
                }
            }

            /*
             * Rename is preferred because it avoids exposing a
             * partially-written index.html.
             */
            if (!temp.renameTo(index)) {

                temp.copyTo(
                    index,
                    overwrite = true
                )

                temp.delete()
            }

            if (
                !index.exists() ||
                index.length() <= 0L
            ) {
                throw IllegalStateException(
                    "Final project file could not be saved"
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

            return index

        } catch (
            error: Exception
        ) {

            try {
                temp.delete()
            } catch (
                _: Exception
            ) {
                // Ignore cleanup failure.
            }

            throw error
        }
    }

    fun readMainFile(
        project: JarvisProject
    ): String? {

        val file =
            File(
                project.directory,
                INDEX_FILE
            )

        if (
            !file.exists() ||
            file.length() <= 0L
        ) {
            return null
        }

        return try {
            file.readText()
        } catch (
            _: Exception
        ) {
            null
        }
    }

    fun currentMainFile():
        File? {

        val project =
            getCurrentProject()
                ?: return null

        val file =
            File(
                project.directory,
                INDEX_FILE
            )

        return file.takeIf {
            it.exists() &&
            it.length() > 0L
        }
    }

    fun previewMainFile():
        File? {

        val project =
            getPreviewProject()
                ?: return null

        val file =
            File(
                project.directory,
                INDEX_FILE
            )

        return file.takeIf {
            it.exists() &&
            it.length() > 0L
        }
    }

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
            current.length() <= 0L
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

        val stamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            ).format(
                Date()
            )

        try {

            current.copyTo(
                File(
                    backupDirectory,
                    "index_$stamp.html"
                ),
                overwrite = false
            )

            trimOldBackups(
                backupDirectory
            )

        } catch (
            _: Exception
        ) {
            // A failed backup should not crash the app.
        }
    }

    private fun trimOldBackups(
        directory: File
    ) {

        directory
            .listFiles()
            ?.filter {
                it.isFile &&
                it.extension.equals(
                    "html",
                    ignoreCase = true
                )
            }
            ?.sortedByDescending {
                it.lastModified()
            }
            ?.drop(
                10
            )
            ?.forEach {

                try {
                    it.delete()
                } catch (
                    _: Exception
                ) {
                    // Ignore cleanup errors.
                }
            }
    }

    // ============================================================
    // AI OUTPUT EXTRACTION
    // ============================================================

    fun extractGeneratedHtml(
        output: String
    ): String? {

        if (output.isBlank()) {
            return null
        }

        val tagged =
            Regex(
                "(?s)<JARVIS_FILE>\\s*(.*?)\\s*</JARVIS_FILE>",
                RegexOption.IGNORE_CASE
            )
                .find(
                    output
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?.trim()

        if (!tagged.isNullOrBlank()) {
            return tagged
        }

        val fenced =
            Regex(
                "(?s)```(?:html)?\\s*(.*?)\\s*```",
                RegexOption.IGNORE_CASE
            )
                .find(
                    output
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
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
                .substring(
                    doctype
                )
                .trim()
        }

        val html =
            output.indexOf(
                "<html",
                ignoreCase = true
            )

        if (html >= 0) {
            return output
                .substring(
                    html
                )
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

        /*
         * Important:
         * truncated AI output commonly contains <body>
         * but never reaches </body>.
         */
        if (!lower.contains("</body>")) {
            problems.add(
                "Missing closing BODY element."
            )
        }

        if (!lower.contains("</html>")) {
            problems.add(
                "Missing closing HTML element."
            )
        }

        when (project.type) {

            JarvisProjectType.WEBSITE -> {

                val viewport =
                    Regex(
                        """<meta\s+[^>]*name\s*=\s*["']viewport["'][^>]*>""",
                        RegexOption.IGNORE_CASE
                    )

                if (
                    !viewport.containsMatchIn(
                        html
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

    fun isFatalValidationProblem(
        problem: String
    ): Boolean {

        return problem.contains(
            "Missing HTML",
            ignoreCase = true
        ) ||
            problem.contains(
                "Missing BODY",
                ignoreCase = true
            ) ||
            problem.contains(
                "Missing closing BODY",
                ignoreCase = true
            ) ||
            problem.contains(
                "Missing closing HTML",
                ignoreCase = true
            ) ||
            problem.contains(
                "mobile viewport",
                ignoreCase = true
            ) ||
            problem.contains(
                "no Canvas",
                ignoreCase = true
            ) ||
            problem.contains(
                "no game script",
                ignoreCase = true
            ) ||
            problem.contains(
                "no animation/game loop",
                ignoreCase = true
            ) ||
            problem.contains(
                "no Three.js",
                ignoreCase = true
            ) ||
            problem.contains(
                "type is unknown",
                ignoreCase = true
            )
    }

    // ============================================================
    // BUILDER SYSTEM PROMPT
    // ============================================================

    fun builderSystemPrompt(
        type: JarvisProjectType
    ): String {

        return when (type) {

            JarvisProjectType.WEBSITE -> """
You are JARVIS BUILDER.
Return exactly one complete compact runnable index.html inside <JARVIS_FILE>...</JARVIS_FILE>.
No markdown, explanations, TODOs, placeholders or omitted code.
Build the minimum complete working page first.
Only add optional visual detail if output space remains.
Use concise mobile-friendly HTML and CSS.
Use minimal JavaScript only when necessary.
Do not use frameworks, large SVGs, base64 assets or unnecessary external assets.
Include a mobile viewport.
Always finish the complete document with </body> and </html>.
""".trimIndent()

            JarvisProjectType.GAME_2D -> """
You are JARVIS BUILDER.
PROJECT TYPE: 2D GAME.
Return one complete compact index.html inside <JARVIS_FILE>...</JARVIS_FILE>.
Preserve the existing Canvas, requestAnimationFrame loop, touch controls, keyboard controls and restart handling.
Modify the existing working foundation instead of rebuilding it.
Preserve working features.
No markdown or explanations.
Always include </body> and </html>.
""".trimIndent()

            JarvisProjectType.GAME_3D -> """
You are JARVIS BUILDER.
PROJECT TYPE: 3D GAME.
Return one complete compact index.html inside <JARVIS_FILE>...</JARVIS_FILE>.
Preserve Three.js, scene, camera, renderer, lighting, requestAnimationFrame, resize handling and touch controls.
Modify the existing working foundation instead of rebuilding it.
Preserve working features.
No markdown or explanations.
Always include </body> and </html>.
""".trimIndent()

            JarvisProjectType.UNKNOWN -> """
You are JARVIS BUILDER.
The project type is unknown.
Ask whether the user wants a WEBSITE, 2D GAME or 3D GAME.
""".trimIndent()
        }
    }

    // ============================================================
    // EDIT PROMPT
    // ============================================================

    fun createEditPrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val existing =
            readMainFile(
                project
            )
                ?: when (project.type) {

                    JarvisProjectType.GAME_2D ->
                        create2DGameFoundation(
                            displayName(
                                project.name
                            )
                        )

                    JarvisProjectType.GAME_3D ->
                        create3DGameFoundation(
                            displayName(
                                project.name
                            )
                        )

                    else ->
                        ""
                }

        return """
EDIT CURRENT ${project.type.name} PROJECT: ${project.name}
REQUEST: $userRequest
Preserve working features and project type.
Return the complete compact index.html, including </body></html>.
<JARVIS_EXISTING_FILE>
$existing
</JARVIS_EXISTING_FILE>
""".trimIndent()
    }

    // ============================================================
    // INITIAL GAME PROMPT
    // ============================================================

    fun createInitialGamePrompt(
        project: JarvisProject,
        userRequest: String
    ): String {

        val foundation =
            when (project.type) {

                JarvisProjectType.GAME_2D ->
                    create2DGameFoundation(
                        displayName(
                            project.name
                        )
                    )

                JarvisProjectType.GAME_3D ->
                    create3DGameFoundation(
                        displayName(
                            project.name
                        )
                    )

                else ->
                    ""
            }

        return """
BUILD ${project.type.name}:
$userRequest

Modify this working foundation.
Preserve the engine, controls and animation loop.
Return one complete compact index.html.

<JARVIS_EXISTING_FILE>
$foundation
</JARVIS_EXISTING_FILE>
""".trimIndent()
    }

    // ============================================================
    // FALLBACK
    // ============================================================

    fun workingFallback(
        project: JarvisProject
    ): String? {

        val existing =
            readMainFile(
                project
            )

        if (existing != null) {

            val fatal =
                validateProject(
                    project,
                    existing
                ).any {
                    isFatalValidationProblem(
                        it
                    )
                }

            if (!fatal) {
                return existing
            }
        }

        return when (project.type) {

            JarvisProjectType.GAME_2D ->
                create2DGameFoundation(
                    displayName(
                        project.name
                    )
                )

            JarvisProjectType.GAME_3D ->
                create3DGameFoundation(
                    displayName(
                        project.name
                    )
                )

            else ->
                null
        }
    }

    /*
     * Kept for compatibility with any existing caller.
     *
     * MainActivity should NOT perform a second expensive LLM pass
     * automatically after a failed generation.
     */
    fun createRepairPrompt(
        project: JarvisProject,
        brokenHtml: String,
        problems: List<String>
    ): String {

        return """
Repair this ${project.type.name} project.

ERRORS:
${problems.joinToString("; ")}

Return ONE complete compact index.html.
Finish the document with </body> and </html>.
No explanation.

<JARVIS_BROKEN_FILE>
$brokenHtml
</JARVIS_BROKEN_FILE>
""".trimIndent()
    }

    // ============================================================
    // 2D GAME FOUNDATION
    // ============================================================

    fun create2DGameFoundation(
        title: String = "Jarvis 2D Game"
    ): String {

        val safeTitle =
            escapeHtml(
                title
            )

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>$safeTitle</title>

<style>
*{box-sizing:border-box}
html,body{
 margin:0;
 background:#050b12;
 color:#fff;
 font-family:Arial,sans-serif;
 overscroll-behavior:none
}
body{
 min-height:100vh;
 display:flex;
 justify-content:center;
 padding:12px;
 touch-action:manipulation
}
#shell{
 width:min(100%,760px)
}
#top{
 display:flex;
 justify-content:space-between;
 align-items:center;
 margin-bottom:8px
}
#wrap{
 position:relative;
 background:#081521;
 border:1px solid #1f5168;
 border-radius:14px;
 overflow:hidden
}
canvas{
 display:block;
 width:100%;
 height:auto;
 aspect-ratio:16/10;
 background:#071019;
 touch-action:none
}
#message{
 position:absolute;
 left:10px;
 right:10px;
 top:10px;
 text-align:center;
 font-weight:700;
 pointer-events:none
}
#controls{
 display:grid;
 grid-template-columns:repeat(3,1fr);
 gap:8px;
 margin-top:10px
}
button{
 min-height:52px;
 border:1px solid #39d9ff;
 border-radius:12px;
 background:#0b1c29;
 color:#fff;
 font-size:18px;
 font-weight:800
}
#restart{
 width:100%;
 margin-top:8px
}
</style>
</head>

<body>

<div id="shell">

<div id="top">
<b>$safeTitle</b>
<div>Score: <span id="score">0</span></div>
</div>

<div id="wrap">
<canvas id="game" width="800" height="500"></canvas>
<div id="message">Collect the yellow coins</div>
</div>

<div id="controls">
<button id="left">◀</button>
<button id="up">▲</button>
<button id="right">▶</button>

<button id="down">▼</button>
<button id="action">ACTION</button>
<button id="pause">PAUSE</button>
</div>

<button id="restart">RESTART</button>

</div>

<script>
const canvas =
 document.getElementById('game');

const ctx =
 canvas.getContext('2d');

const scoreEl =
 document.getElementById('score');

const messageEl =
 document.getElementById('message');

const keys = {
 left:false,
 right:false,
 up:false,
 down:false
};

let score = 0;
let paused = false;
let last = performance.now();

const player = {
 x:100,
 y:250,
 r:20,
 speed:260
};

const coin = {
 x:500,
 y:250,
 r:13
};

function randomCoin(){

 coin.x =
  coin.r +
  Math.random() *
  (canvas.width - coin.r * 2);

 coin.y =
  coin.r +
  Math.random() *
  (canvas.height - coin.r * 2);
}

function resetGame(){

 score = 0;

 scoreEl.textContent =
  '0';

 player.x = 100;
 player.y = 250;

 paused = false;

 messageEl.textContent =
  'Collect the yellow coins';

 randomCoin();
}

function update(dt){

 if(paused){
  return;
 }

 let dx = 0;
 let dy = 0;

 if(keys.left){
  dx--;
 }

 if(keys.right){
  dx++;
 }

 if(keys.up){
  dy--;
 }

 if(keys.down){
  dy++;
 }

 if(dx || dy){

  const length =
   Math.hypot(
    dx,
    dy
   ) || 1;

  player.x +=
   dx /
   length *
   player.speed *
   dt;

  player.y +=
   dy /
   length *
   player.speed *
   dt;
 }

 player.x =
  Math.max(
   player.r,
   Math.min(
    canvas.width -
    player.r,
    player.x
   )
  );

 player.y =
  Math.max(
   player.r,
   Math.min(
    canvas.height -
    player.r,
    player.y
   )
  );

 if(
  Math.hypot(
   player.x - coin.x,
   player.y - coin.y
  ) <
  player.r + coin.r
 ){

  score++;

  scoreEl.textContent =
   String(score);

  messageEl.textContent =
   'Nice! Score ' + score;

  randomCoin();
 }
}

function draw(){

 ctx.fillStyle =
  '#07131d';

 ctx.fillRect(
  0,
  0,
  canvas.width,
  canvas.height
 );

 ctx.strokeStyle =
  '#12384d';

 for(
  let x = 0;
  x < canvas.width;
  x += 50
 ){

  ctx.beginPath();

  ctx.moveTo(
   x,
   0
  );

  ctx.lineTo(
   x,
   canvas.height
  );

  ctx.stroke();
 }

 for(
  let y = 0;
  y < canvas.height;
  y += 50
 ){

  ctx.beginPath();

  ctx.moveTo(
   0,
   y
  );

  ctx.lineTo(
   canvas.width,
   y
  );

  ctx.stroke();
 }

 ctx.beginPath();

 ctx.fillStyle =
  '#ffd43b';

 ctx.arc(
  coin.x,
  coin.y,
  coin.r,
  0,
  Math.PI * 2
 );

 ctx.fill();

 ctx.beginPath();

 ctx.fillStyle =
  '#32d7ff';

 ctx.arc(
  player.x,
  player.y,
  player.r,
  0,
  Math.PI * 2
 );

 ctx.fill();
}

function gameLoop(now){

 const dt =
  Math.min(
   (now - last) /
   1000,
   .05
  );

 last = now;

 update(
  dt
 );

 draw();

 requestAnimationFrame(
  gameLoop
 );
}

function bindHold(
 id,
 key
){

 const button =
  document.getElementById(
   id
  );

 const down =
  event => {

   event.preventDefault();

   keys[key] =
    true;
  };

 const up =
  event => {

   event.preventDefault();

   keys[key] =
    false;
  };

 button.addEventListener(
  'pointerdown',
  down
 );

 button.addEventListener(
  'pointerup',
  up
 );

 button.addEventListener(
  'pointercancel',
  up
 );

 button.addEventListener(
  'pointerleave',
  up
 );
}

bindHold(
 'left',
 'left'
);

bindHold(
 'right',
 'right'
);

bindHold(
 'up',
 'up'
);

bindHold(
 'down',
 'down'
);

document
 .getElementById(
  'pause'
 )
 .onclick =
 () => {

  paused =
   !paused;

  messageEl.textContent =
   paused ?
   'Paused' :
   'Go!';
 };

document
 .getElementById(
  'action'
 )
 .onclick =
 () => {

  messageEl.textContent =
   'Action!';
 };

document
 .getElementById(
  'restart'
 )
 .onclick =
 resetGame;

window.addEventListener(
 'keydown',
 event => {

  if(
   event.key ===
   'ArrowLeft' ||
   event.key ===
   'a'
  ){
   keys.left = true;
  }

  if(
   event.key ===
   'ArrowRight' ||
   event.key ===
   'd'
  ){
   keys.right = true;
  }

  if(
   event.key ===
   'ArrowUp' ||
   event.key ===
   'w'
  ){
   keys.up = true;
  }

  if(
   event.key ===
   'ArrowDown' ||
   event.key ===
   's'
  ){
   keys.down = true;
  }
 }
);

window.addEventListener(
 'keyup',
 event => {

  if(
   event.key ===
   'ArrowLeft' ||
   event.key ===
   'a'
  ){
   keys.left = false;
  }

  if(
   event.key ===
   'ArrowRight' ||
   event.key ===
   'd'
  ){
   keys.right = false;
  }

  if(
   event.key ===
   'ArrowUp' ||
   event.key ===
   'w'
  ){
   keys.up = false;
  }

  if(
   event.key ===
   'ArrowDown' ||
   event.key ===
   's'
  ){
   keys.down = false;
  }
 }
);

resetGame();

requestAnimationFrame(
 gameLoop
);
</script>

</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // 3D GAME FOUNDATION
    // ============================================================

    fun create3DGameFoundation(
        title: String = "Jarvis 3D Game"
    ): String {

        val safeTitle =
            escapeHtml(
                title
            )

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>$safeTitle</title>

<style>
*{box-sizing:border-box}

html,body{
 margin:0;
 background:#030811;
 color:#fff;
 font-family:Arial,sans-serif
}

body{
 padding:10px
}

#shell{
 width:min(100%,900px);
 margin:auto
}

#top{
 display:flex;
 justify-content:space-between;
 margin-bottom:8px
}

#game{
 position:relative;
 width:100%;
 aspect-ratio:16/10;
 background:#07111c;
 border:1px solid #24546b;
 border-radius:14px;
 overflow:hidden
}

#game canvas{
 display:block;
 width:100%!important;
 height:100%!important;
 touch-action:none
}

#status{
 position:absolute;
 left:10px;
 right:10px;
 top:10px;
 text-align:center;
 z-index:5;
 font-weight:700
}

#controls{
 display:grid;
 grid-template-columns:repeat(3,1fr);
 gap:8px;
 margin-top:10px
}

button{
 min-height:52px;
 border:1px solid #39d9ff;
 border-radius:12px;
 background:#0b1c29;
 color:#fff;
 font-size:18px;
 font-weight:800
}

#restart{
 width:100%;
 margin-top:8px
}
</style>
</head>

<body>

<div id="shell">

<div id="top">
<div><b>$safeTitle</b></div>
<div>Score: <span id="score">0</span></div>
</div>

<div id="game">
<div id="status">Loading 3D engine...</div>
</div>

<div id="controls">
<button id="left">◀</button>
<button id="forward">▲</button>
<button id="right">▶</button>

<button id="back">▼</button>
<button id="action">ACTION</button>
<button id="pause">PAUSE</button>
</div>

<button id="restart">RESTART</button>

</div>

<script type="module">

const statusEl =
 document.getElementById(
  'status'
 );

const scoreEl =
 document.getElementById(
  'score'
 );

const gameEl =
 document.getElementById(
  'game'
 );

let THREE;

try {

 THREE =
  await import(
   'https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js'
  );

} catch(error) {

 statusEl.textContent =
  '3D engine could not load. Check internet connection.';

 throw error;
}

const scene =
 new THREE.Scene();

scene.background =
 new THREE.Color(
  0x07111c
 );

scene.fog =
 new THREE.Fog(
  0x07111c,
  18,
  55
 );

const camera =
 new THREE.PerspectiveCamera(
  60,
  gameEl.clientWidth /
  Math.max(
   gameEl.clientHeight,
   1
  ),
  .1,
  100
 );

const renderer =
 new THREE.WebGLRenderer({
  antialias:true
 });

renderer.setPixelRatio(
 Math.min(
  window.devicePixelRatio ||
  1,
  2
 )
);

gameEl.appendChild(
 renderer.domElement
);

scene.add(
 new THREE.HemisphereLight(
  0x9edfff,
  0x172014,
  2
 )
);

const sun =
 new THREE.DirectionalLight(
  0xffffff,
  2
 );

sun.position.set(
 5,
 10,
 5
);

scene.add(
 sun
);

const floor =
 new THREE.Mesh(
  new THREE.PlaneGeometry(
   50,
   50
  ),
  new THREE.MeshStandardMaterial({
   color:0x163321
  })
 );

floor.rotation.x =
 -Math.PI / 2;

scene.add(
 floor
);

scene.add(
 new THREE.GridHelper(
  50,
  25,
  0x2a667c,
  0x24452f
 )
);

const player =
 new THREE.Mesh(
  new THREE.CapsuleGeometry(
   .55,
   1,
   5,
   10
  ),
  new THREE.MeshStandardMaterial({
   color:0x32d7ff
  })
 );

player.position.set(
 0,
 1,
 0
);

scene.add(
 player
);

const target =
 new THREE.Mesh(
  new THREE.SphereGeometry(
   .5,
   18,
   18
  ),
  new THREE.MeshStandardMaterial({
   color:0xffd43b
  })
 );

scene.add(
 target
);

const keys = {
 left:false,
 right:false,
 forward:false,
 back:false
};

let score = 0;
let paused = false;
let previous =
 performance.now();

function moveTarget(){

 target.position.set(
  (Math.random() - .5) * 18,
  .6,
  (Math.random() - .5) * 18
 );
}

function resetGame(){

 score = 0;

 scoreEl.textContent =
  '0';

 player.position.set(
  0,
  1,
  0
 );

 paused = false;

 moveTarget();

 statusEl.textContent =
  'Collect the yellow orb';
}

function update(dt){

 if(paused){
  return;
 }

 let x = 0;
 let z = 0;

 if(keys.left){
  x--;
 }

 if(keys.right){
  x++;
 }

 if(keys.forward){
  z--;
 }

 if(keys.back){
  z++;
 }

 if(x || z){

  const length =
   Math.hypot(
    x,
    z
   ) || 1;

  player.position.x +=
   x /
   length *
   6 *
   dt;

  player.position.z +=
   z /
   length *
   6 *
   dt;
 }

 player.position.x =
  THREE.MathUtils.clamp(
   player.position.x,
   -20,
   20
  );

 player.position.z =
  THREE.MathUtils.clamp(
   player.position.z,
   -20,
   20
  );

 if(
  Math.hypot(
   player.position.x -
   target.position.x,
   player.position.z -
   target.position.z
  ) < 1.2
 ){

  score++;

  scoreEl.textContent =
   String(
    score
   );

  statusEl.textContent =
   'Nice! Score ' +
   score;

  moveTarget();
 }

 const desired =
  new THREE.Vector3(
   player.position.x,
   7,
   player.position.z + 10
  );

 camera.position.lerp(
  desired,
  .08
 );

 camera.lookAt(
  player.position.x,
  .8,
  player.position.z
 );
}

function animate(now){

 const dt =
  Math.min(
   (now - previous) /
   1000,
   .05
  );

 previous =
  now;

 update(
  dt
 );

 target.rotation.y +=
  dt * 2;

 renderer.render(
  scene,
  camera
 );

 requestAnimationFrame(
  animate
 );
}

function resize(){

 const width =
  gameEl.clientWidth;

 const height =
  Math.max(
   gameEl.clientHeight,
   1
  );

 camera.aspect =
  width /
  height;

 camera.updateProjectionMatrix();

 renderer.setSize(
  width,
  height
 );
}

function bindHold(
 id,
 key
){

 const button =
  document.getElementById(
   id
  );

 const down =
  event => {

   event.preventDefault();

   keys[key] =
    true;
  };

 const up =
  event => {

   event.preventDefault();

   keys[key] =
    false;
  };

 button.addEventListener(
  'pointerdown',
  down
 );

 button.addEventListener(
  'pointerup',
  up
 );

 button.addEventListener(
  'pointercancel',
  up
 );

 button.addEventListener(
  'pointerleave',
  up
 );
}

bindHold(
 'left',
 'left'
);

bindHold(
 'right',
 'right'
);

bindHold(
 'forward',
 'forward'
);

bindHold(
 'back',
 'back'
);

document
 .getElementById(
  'pause'
 )
 .onclick =
 () => {

  paused =
   !paused;

  statusEl.textContent =
   paused ?
   'Paused' :
   'Go!';
 };

document
 .getElementById(
  'action'
 )
 .onclick =
 () => {

  statusEl.textContent =
   'Action!';
 };

document
 .getElementById(
  'restart'
 )
 .onclick =
 resetGame;

window.addEventListener(
 'resize',
 resize
);

resize();

resetGame();

requestAnimationFrame(
 animate
);

</script>

</body>
</html>
""".trimIndent()
    }

    // ============================================================
    // PROJECT INFO
    // ============================================================

    private fun writeProjectInfo(
        project: JarvisProject
    ) {

        File(
            project.directory,
            PROJECT_INFO_FILE
        ).writeText(
            """
id=${project.id}
name=${project.name}
type=${project.type.name}
created=${System.currentTimeMillis()}
""".trimIndent()
        )
    }

    // ============================================================
    // PROJECT NAME
    // ============================================================

    fun suggestedProjectName(
        request: String,
        type: JarvisProjectType
    ): String {

        /*
         * Example:
         *
         * "Create a website called Nova Test with a dark design"
         *
         * becomes:
         *
         * nova_test
         *
         * instead of:
         *
         * nova_test_with_a_dark_design
         */
        val called =
            Regex(
                """\b(?:called|named)\s+([a-z0-9][a-z0-9 '&-]{0,39}?)(?=\s+(?:with|that|which|using|featuring|for)\b|[.!?,]|$)""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    request
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?.trim()

        if (!called.isNullOrBlank()) {

            return sanitizeProjectName(
                called
            )
        }

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
                "develop",
                "generate",
                "start",
                "new",
                "another",
                "a website",
                "website",
                "web site",
                "webpage",
                "web page",
                "landing page",
                "portfolio site",
                "business site",
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

        /*
         * Remove the descriptive part of the request so a sentence
         * does not become a huge project name.
         */
        cleaned =
            cleaned
                .replace(
                    Regex(
                        """\b(?:with|that|which|using|featuring|for)\b.*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "[^a-z0-9 ]"
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "\\s+"
                    ),
                    " "
                )
                .trim()

        val words =
            cleaned
                .split(
                    " "
                )
                .filter {
                    it.isNotBlank()
                }
                .take(
                    4
                )

        if (words.isNotEmpty()) {

            return sanitizeProjectName(
                words.joinToString(
                    "_"
                )
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
                    Regex(
                        "[^a-z0-9_-]"
                    ),
                    "_"
                )
                .replace(
                    Regex(
                        "_+"
                    ),
                    "_"
                )
                .trim(
                    '_'
                )
                .take(
                    50
                )

        return cleaned.ifBlank {
            "jarvis_project"
        }
    }

    private fun displayName(
        name: String
    ): String {

        return name
            .replace(
                "_",
                " "
            )
            .split(
                " "
            )
            .filter {
                it.isNotBlank()
            }
            .joinToString(
                " "
            ) {
                it.replaceFirstChar { character ->
                    character.uppercase()
                }
            }
            .ifBlank {
                "Jarvis Game"
            }
    }

    private fun escapeHtml(
        value: String
    ): String {

        return value
            .replace(
                "&",
                "&amp;"
            )
            .replace(
                "<",
                "&lt;"
            )
            .replace(
                ">",
                "&gt;"
            )
            .replace(
                "\"",
                "&quot;"
            )
            .replace(
                "'",
                "&#39;"
            )
    }
}
