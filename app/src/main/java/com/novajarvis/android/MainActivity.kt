package com.novajarvis.android

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var chatText: TextView
    private lateinit var inputText: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var sendButton: Button
    private lateinit var talkButton: Button
    private lateinit var modelButton: Button
    private lateinit var buildButton: Button
    private lateinit var previewButton: Button

    private lateinit var workspace: ProjectWorkspace

    private var builderMode = false
    private var tts: TextToSpeech? = null

    private var modelReady = false
    private var modelLoaded = false
    private var modelLoading = false
    private var generationRunning = false

    private var llamaModel: LlamaModel? = null

    private val downloadExecutor =
        Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences(
            "jarvis_memory",
            MODE_PRIVATE
        )
    }

    private val modelFile by lazy {
        File(
            filesDir,
            "models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        )
    }

    private val partialModelFile by lazy {
        File(
            filesDir,
            "models/qwen2.5-0.5b-instruct-q4_k_m.gguf.part"
        )
    }

    private val MODEL_URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"

    // ============================================================
    // VOICE
    // ============================================================

    private val speechLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK) {

                val results =
                    result.data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )

                val spoken =
                    results?.firstOrNull()

                if (!spoken.isNullOrBlank()) {

                    inputText.setText(spoken)

                    inputText.setSelection(
                        spoken.length
                    )

                    sendMessage()
                }
            }
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startVoiceInput()

            } else {

                setStatus(
                    "MICROPHONE PERMISSION DENIED"
                )

                addJarvisMessage(
                    "I need microphone permission before TALK can work."
                )
            }
        }

    // ============================================================
    // CREATE
    // ============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        workspace =
            ProjectWorkspace(this)

        tts =
            TextToSpeech(
                this,
                this
            )

        buildInterface()

        restoreMemory()

        /*
         * Check for a build that was running when Android
         * terminated the previous app process.
         */
        recoverInterruptedBuild()

        checkModel()

        addJarvisMessage(
            "JARVIS Android initialized."
        )

        workspace
            .getCurrentProject()
            ?.let { current ->

                addJarvisMessage(
                    "Current workspace: " +
                        "${current.name} " +
                        "(${projectLabel(current.type)})."
                )
            }

        workspace
            .getPreviewProject()
            ?.let { preview ->

                addJarvisMessage(
                    "Verified preview available: " +
                        "${preview.name} " +
                        "(${projectLabel(preview.type)})."
                )
            }

        if (modelReady) {

            loadLocalModel()

        } else {

            addJarvisMessage(
                "The local AI model is not installed yet. " +
                    "Press MODEL to download it."
            )
        }
    }

    // ============================================================
    // RECOVERY
    // ============================================================

    private fun recoverInterruptedBuild() {

        if (
            !workspace.wasBuildInterrupted()
        ) {

            return
        }

        val interrupted =
            workspace.getInterruptedProject()

        /*
         * Clear the stale running flag immediately.
         *
         * The project files themselves are preserved.
         */
        workspace.clearInterruptedBuild()

        if (interrupted == null) {

            addJarvisMessage(
                "I detected an interrupted build. " +
                    "Your last verified preview was kept safe."
            )

            return
        }

        val html =
            workspace.readMainFile(
                interrupted
            )

        if (
            !html.isNullOrBlank()
        ) {

            val problems =
                workspace.validateProject(
                    interrupted,
                    html
                )

            val fatal =
                problems.any {
                    workspace.isFatalValidationProblem(
                        it
                    )
                }

            if (!fatal) {

                workspace.markProjectVerified(
                    interrupted
                )

                addJarvisMessage(
                    "I recovered ${interrupted.name} after the " +
                        "previous interruption. Its saved project " +
                        "passed validation and is ready to preview."
                )

                return
            }
        }

        addJarvisMessage(
            "The previous build of ${interrupted.name} was interrupted " +
                "before a complete project was saved. I did not replace " +
                "your verified Preview with the incomplete build."
        )
    }

    // ============================================================
    // UI
    // ============================================================

    private fun buildInterface() {

        val background =
            Color.rgb(
                3,
                10,
                18
            )

        val panel =
            Color.rgb(
                8,
                20,
                31
            )

        val cyan =
            Color.rgb(
                0,
                217,
                255
            )

        val white =
            Color.rgb(
                235,
                248,
                255
            )

        val muted =
            Color.rgb(
                130,
                170,
                185
            )

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    background
                )

                setPadding(
                    dp(16),
                    dp(20),
                    dp(16),
                    dp(16)
                )
            }

        val title =
            TextView(this).apply {

                text =
                    "J A R V I S"

                textSize =
                    28f

                setTextColor(
                    cyan
                )

                gravity =
                    Gravity.CENTER

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    dp(10),
                    0,
                    dp(4)
                )
            }

        val subtitle =
            TextView(this).apply {

                text =
                    "PRIVATE AI + WEB BUILDER"

                textSize =
                    12f

                setTextColor(
                    muted
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    0,
                    0,
                    dp(12)
                )
            }

        statusText =
            TextView(this).apply {

                text =
                    "SYSTEM: STARTING"

                textSize =
                    12f

                setTextColor(
                    cyan
                )

                setBackgroundColor(
                    panel
                )

                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )
            }

        progressBar =
            ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {

                max =
                    100

                progress =
                    0

                visibility =
                    View.GONE
            }

        val scroll =
            ScrollView(this).apply {

                isFillViewport =
                    true

                setBackgroundColor(
                    panel
                )
            }

        chatText =
            TextView(this).apply {

                textSize =
                    15f

                setTextColor(
                    white
                )

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                setTextIsSelectable(
                    true
                )
            }

        scroll.addView(
            chatText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        inputText =
            EditText(this).apply {

                hint =
                    "Ask Jarvis..."

                setHintTextColor(
                    muted
                )

                setTextColor(
                    white
                )

                setBackgroundColor(
                    panel
                )

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )

                maxLines =
                    5
            }

        val normalRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        talkButton =
            makeButton(
                "TALK",
                cyan,
                background
            )

        sendButton =
            makeButton(
                "SEND",
                cyan,
                background
            )

        modelButton =
            makeButton(
                "MODEL",
                cyan,
                background
            )

        addWeightedButton(
            normalRow,
            talkButton
        )

        addWeightedButton(
            normalRow,
            sendButton
        )

        addWeightedButton(
            normalRow,
            modelButton
        )

        val builderRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        buildButton =
            makeButton(
                "BUILD",
                cyan,
                background
            )

        previewButton =
            makeButton(
                "PREVIEW",
                cyan,
                background
            )

        addWeightedButton(
            builderRow,
            buildButton
        )

        addWeightedButton(
            builderRow,
            previewButton
        )

        root.addView(
            title
        )

        root.addView(
            subtitle
        )

        root.addView(
            statusText
        )

        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply {

                topMargin =
                    dp(6)
            }
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {

                topMargin =
                    dp(10)

                bottomMargin =
                    dp(10)
            }
        )

        root.addView(
            inputText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                bottomMargin =
                    dp(8)
            }
        )

        root.addView(
            normalRow
        )

        root.addView(
            builderRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {

                topMargin =
                    dp(6)
            }
        )

        setContentView(
            root
        )

        sendButton.setOnClickListener {

            sendMessage()
        }

        talkButton.setOnClickListener {

            requestVoiceInput()
        }

        buildButton.setOnClickListener {

            if (generationRunning) {

                addJarvisMessage(
                    "A build is already running."
                )

                return@setOnClickListener
            }

            builderMode =
                !builderMode

            updateBuilderButton()

            if (builderMode) {

                addJarvisMessage(
                    "Builder Mode enabled. Tell me to create a website, " +
                        "a 2D game, or a 3D game."
                )

                setStatus(
                    "BUILDER: READY"
                )

            } else {

                addJarvisMessage(
                    "Builder Mode disabled. Normal Jarvis chat restored."
                )

                setStatus(
                    "SYSTEM: AI READY"
                )
            }
        }

        previewButton.setOnClickListener {

            previewVerifiedProject()
        }

        modelButton.setOnClickListener {

            when {

                modelLoaded ->

                    addJarvisMessage(
                        "The local Jarvis AI model is loaded and ready."
                    )

                modelReady ->

                    loadLocalModel()

                else ->

                    downloadModel()
            }
        }
    }

    private fun addWeightedButton(
        row: LinearLayout,
        button: Button
    ) {

        row.addView(
            button,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {

                marginStart =
                    dp(3)

                marginEnd =
                    dp(3)
            }
        )
    }

    private fun makeButton(
        label: String,
        textColor: Int,
        backgroundColor: Int
    ): Button {

        return Button(this).apply {

            text =
                label

            setTextColor(
                textColor
            )

            setBackgroundColor(
                backgroundColor
            )

            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            isAllCaps =
                false
        }
    }

    private fun updateBuilderButton() {

        buildButton.text =
            if (builderMode) {
                "BUILD ✓"
            } else {
                "BUILD"
            }
    }

    // ============================================================
    // MESSAGE ROUTING
    // ============================================================

    private fun sendMessage() {

        if (generationRunning) {

            addJarvisMessage(
                "I'm still finishing the current request."
            )

            return
        }

        val message =
            inputText
                .text
                .toString()
                .trim()

        if (message.isEmpty()) {
            return
        }

        inputText.setText(
            ""
        )

        addUserMessage(
            message
        )

        rememberLastUserMessage(
            message
        )

        if (!modelReady) {

            addJarvisMessage(
                "My local AI model hasn't been downloaded yet. " +
                    "Press MODEL first."
            )

            return
        }

        if (!modelLoaded) {

            addJarvisMessage(
                "I'm loading my local AI model. Please try again when " +
                    "the status says AI READY."
            )

            loadLocalModel()

            return
        }

        val isNewBuild =
            workspace.isNewBuildRequest(
                message
            )

        val isEdit =
            workspace.isProjectEditRequest(
                message
            )

        if (
            builderMode ||
            isNewBuild ||
            isEdit
        ) {

            handleBuilderRequest(
                message
            )

        } else {

            generateAIResponse(
                message
            )
        }
    }

    // ============================================================
    // BUILDER ROUTING
    // ============================================================

    private fun handleBuilderRequest(
        message: String
    ) {

        val explicitType =
            workspace.detectProjectType(
                message
            )

        val current =
            workspace.getCurrentProject()

        val newBuild =
            workspace.isNewBuildRequest(
                message
            )

        // ========================================================
        // NEW PROJECT
        // ========================================================

        if (newBuild) {

            if (
                explicitType ==
                JarvisProjectType.UNKNOWN
            ) {

                addJarvisMessage(
                    "Would you like me to build that as a WEBSITE, " +
                        "2D GAME, or 3D GAME?"
                )

                setStatus(
                    "BUILDER: PROJECT TYPE NEEDED"
                )

                builderMode =
                    true

                updateBuilderButton()

                return
            }

            val name =
                workspace.suggestedProjectName(
                    message,
                    explicitType
                )

            val project =
                try {

                    workspace.createProject(
                        name,
                        explicitType
                    )

                } catch (
                    e: Exception
                ) {

                    addJarvisMessage(
                        "I couldn't create the project workspace: " +
                            (
                                e.message ?:
                                "Unknown storage error"
                            )
                    )

                    setStatus(
                        "BUILDER: STORAGE ERROR"
                    )

                    return
                }

            builderMode =
                true

            updateBuilderButton()

            if (
                isGameProject(
                    project
                )
            ) {

                setStatus(
                    "BUILDER: GAME READY"
                )

                addJarvisMessage(
                    "${project.name} is ready as a " +
                        "${projectLabel(project.type)}. " +
                        "I created a verified playable foundation instantly. " +
                        "Press PREVIEW to play it, then tell me what " +
                        "you want changed."
                )

                return
            }

            generateProject(
                project,
                message,
                false
            )

            return
        }

        // ========================================================
        // EXISTING PROJECT
        // ========================================================

        if (current != null) {

            if (
                explicitType !=
                    JarvisProjectType.UNKNOWN &&
                explicitType !=
                    current.type
            ) {

                addJarvisMessage(
                    "Your current project is a " +
                        "${projectLabel(current.type)}. " +
                        "You mentioned ${projectLabel(explicitType)}. " +
                        "Say \"create a new ${projectLabel(explicitType)}\" " +
                        "if you want a separate project."
                )

                setStatus(
                    "BUILDER: TYPE PROTECTED"
                )

                return
            }

            builderMode =
                true

            updateBuilderButton()

            generateProject(
                current,
                message,
                true
            )

            return
        }

        // ========================================================
        // NO CURRENT PROJECT
        // ========================================================

        if (
            explicitType ==
            JarvisProjectType.UNKNOWN
        ) {

            addJarvisMessage(
                "Tell me whether you want a WEBSITE, " +
                    "2D GAME, or 3D GAME."
            )

            builderMode =
                true

            updateBuilderButton()

            setStatus(
                "BUILDER: PROJECT TYPE NEEDED"
            )

            return
        }

        val name =
            workspace.suggestedProjectName(
                message,
                explicitType
            )

        val project =
            try {

                workspace.createProject(
                    name,
                    explicitType
                )

            } catch (
                e: Exception
            ) {

                addJarvisMessage(
                    "I couldn't create the project workspace: " +
                        (
                            e.message ?:
                            "Unknown storage error"
                        )
                )

                setStatus(
                    "BUILDER: STORAGE ERROR"
                )

                return
            }

        builderMode =
            true

        updateBuilderButton()

        if (
            isGameProject(
                project
            )
        ) {

            setStatus(
                "BUILDER: GAME READY"
            )

            addJarvisMessage(
                "${project.name} is ready as a " +
                    "${projectLabel(project.type)}. " +
                    "Press PREVIEW to play it."
            )

            return
        }

        generateProject(
            project,
            message,
            false
        )
    }

    // ============================================================
    // PHONE-SAFE BUILDER
    // ============================================================

    private fun isGameProject(
        project: JarvisProject
    ): Boolean {

        return (
            project.type ==
                JarvisProjectType.GAME_2D ||
            project.type ==
                JarvisProjectType.GAME_3D
        )
    }

    /*
     * Lower output limits reduce peak memory use.
     *
     * The 0.5B model can still make useful compact pages,
     * but asking it for a huge page on-device increases the
     * chance Android kills the process.
     */
    private fun builderTokenLimit(
        project: JarvisProject
    ): Int {

        return when (
            project.type
        ) {

            JarvisProjectType.WEBSITE ->
                896

            JarvisProjectType.GAME_2D ->
                320

            JarvisProjectType.GAME_3D ->
                320

            JarvisProjectType.UNKNOWN ->
                256
        }
    }

    // ============================================================
    // PROJECT GENERATION
    // ============================================================

    private fun generateProject(
        project: JarvisProject,
        userRequest: String,
        editing: Boolean
    ) {

        if (generationRunning) {

            addJarvisMessage(
                "A project generation is already running."
            )

            return
        }

        val model =
            llamaModel

        if (model == null) {

            modelLoaded =
                false

            setStatus(
                "SYSTEM: MODEL NOT LOADED"
            )

            return
        }

        /*
         * New games already have their verified foundation.
         */
        if (
            !editing &&
            isGameProject(project)
        ) {

            setStatus(
                "BUILDER: GAME READY"
            )

            addJarvisMessage(
                "${project.name} already has its working " +
                    "${projectLabel(project.type)} foundation. " +
                    "Press PREVIEW to play it."
            )

            return
        }

        generationRunning =
            true

        setBusy(
            true
        )

        /*
         * Persist this BEFORE inference.
         *
         * If Android terminates the app, the next launch knows
         * exactly which project was interrupted.
         */
        workspace.beginBuild(
            project
        )

        setStatus(
            if (editing) {

                "BUILDER: UPDATING " +
                    projectLabel(
                        project.type
                    )

            } else {

                "BUILDER: CREATING " +
                    projectLabel(
                        project.type
                    )
            }
        )

        addJarvisMessage(
            if (editing) {

                "Updating ${project.name}. " +
                    "I'll preserve its existing verified version " +
                    "until the update passes validation."

            } else {

                "Creating ${project.name} as a " +
                    "${projectLabel(project.type)}. " +
                    "It won't replace Preview until the build " +
                    "has finished and passed validation."
            }
        )

        lifecycleScope.launch {

            var successful =
                false

            try {

                val prompt =
                    if (editing) {

                        workspace.createEditPrompt(
                            project,
                            userRequest
                        )

                    } else {

                        """
PROJECT NAME:
${project.name}

PROJECT TYPE:
${project.type.name}

USER REQUEST:
$userRequest

Create ONE complete compact index.html.

IMPORTANT:
Keep the page lightweight.
Use short CSS.
Use lightweight JavaScript only when necessary.
Do not use large external assets.
Include the mobile viewport.
Finish the entire document including </body> and </html>.

Return exactly:

<JARVIS_FILE>
COMPLETE HTML
</JARVIS_FILE>
""".trimIndent()
                    }

                setStatus(
                    "BUILDER: GENERATING"
                )

                val result =
                    withContext(
                        Dispatchers.Default
                    ) {

                        Llama.complete(
                            model,
                            prompt =
                                prompt,
                            systemPrompt =
                                workspace.builderSystemPrompt(
                                    project.type
                                ),
                            maxTokens =
                                builderTokenLimit(
                                    project
                                )
                        )
                    }

                /*
                 * Keep only the generated text reference we need.
                 */
                val raw =
                    result
                        .text
                        .trim()

                var html =
                    workspace.extractGeneratedHtml(
                        raw
                    )

                if (
                    html.isNullOrBlank()
                ) {

                    addJarvisMessage(
                        "The builder stopped before returning a complete " +
                            "project. Nothing incomplete was promoted to Preview."
                    )

                    setStatus(
                        "BUILDER: OUTPUT INCOMPLETE"
                    )

                    return@launch
                }

                /*
                 * Reject suspiciously incomplete HTML before saving.
                 */
                if (
                    !html.contains(
                        "</html>",
                        ignoreCase = true
                    )
                ) {

                    addJarvisMessage(
                        "The generated page ended early, so I rejected it " +
                            "instead of replacing your verified Preview."
                    )

                    setStatus(
                        "BUILDER: OUTPUT CUT OFF"
                    )

                    return@launch
                }

                val problems =
                    workspace.validateProject(
                        project,
                        html
                    )

                val fatal =
                    problems.any {

                        workspace
                            .isFatalValidationProblem(
                                it
                            )
                    }

                if (fatal) {

                    /*
                     * IMPORTANT PHONE-SAFE CHANGE:
                     *
                     * Do not immediately run a second expensive LLM
                     * inference on Android.
                     */
                    addJarvisMessage(
                        "The generated project didn't pass validation: " +
                            problems.joinToString(
                                "; "
                            ) +
                            ". I rejected it and kept the previous " +
                            "verified Preview safe."
                    )

                    setStatus(
                        "BUILDER: VALIDATION FAILED"
                    )

                    return@launch
                }

                setStatus(
                    "BUILDER: SAVING SAFELY"
                )

                /*
                 * ProjectWorkspace writes to a temporary file,
                 * validates it, then replaces index.html.
                 */
                workspace.saveMainFileAtomic(
                    project,
                    html,
                    makePreviewable = true
                )

                successful =
                    true

                if (
                    problems.isEmpty()
                ) {

                    addJarvisMessage(
                        "${project.name} is built, verified and saved. " +
                            "PREVIEW now points to this " +
                            "${projectLabel(project.type)}."
                    )

                    setStatus(
                        "BUILDER: PROJECT READY"
                    )

                } else {

                    addJarvisMessage(
                        "${project.name} is saved and previewable. " +
                            "Non-fatal warnings: " +
                            problems.joinToString(
                                "; "
                            )
                    )

                    setStatus(
                        "BUILDER: READY WITH WARNINGS"
                    )
                }

                builderMode =
                    true

                updateBuilderButton()

            } catch (
                e: Exception
            ) {

                addJarvisMessage(
                    "The build stopped: " +
                        (
                            e.message ?:
                            "Unknown builder error"
                        ) +
                        ". I kept the last verified Preview safe."
                )

                setStatus(
                    "BUILDER: BUILD STOPPED"
                )

            } finally {

                /*
                 * Clear the build marker on normal coroutine completion.
                 *
                 * If Android kills the entire process during inference,
                 * this code never runs and beginBuild() remains stored.
                 * That is what lets recoverInterruptedBuild() detect it
                 * next time.
                 */
                workspace.finishBuild(
                    project,
                    successful
                )

                generationRunning =
                    false

                setBusy(
                    false
                )
            }
        }
    }

    // ============================================================
    // VERIFIED PREVIEW
    // ============================================================

    private fun previewVerifiedProject() {

        /*
         * Never use getCurrentProject() here.
         *
         * Current may be a website that is only half built.
         */
        val project =
            workspace.getPreviewProject()

        if (project == null) {

            val current =
                workspace.getCurrentProject()

            if (
                current != null &&
                current.type ==
                    JarvisProjectType.WEBSITE
            ) {

                addJarvisMessage(
                    "${current.name} doesn't have a verified website " +
                        "build yet. Build it successfully first, then PREVIEW " +
                        "will open that website."
                )

            } else {

                addJarvisMessage(
                    "There isn't a verified project to preview yet."
                )
            }

            setStatus(
                "PREVIEW: NOTHING VERIFIED"
            )

            return
        }

        val html =
            workspace.readMainFile(
                project
            )

        if (
            html.isNullOrBlank()
        ) {

            addJarvisMessage(
                "The verified project file could not be read."
            )

            setStatus(
                "PREVIEW: FILE ERROR"
            )

            return
        }

        val problems =
            workspace.validateProject(
                project,
                html
            )

        val fatal =
            problems.any {

                workspace.isFatalValidationProblem(
                    it
                )
            }

        if (fatal) {

            addJarvisMessage(
                "Preview was blocked because the saved project " +
                    "no longer passes validation."
            )

            setStatus(
                "PREVIEW: VALIDATION FAILED"
            )

            return
        }

        showProjectPreview(
            project,
            html
        )
    }

    private fun showProjectPreview(
        project: JarvisProject,
        html: String
    ) {

        try {

            val dialog =
                Dialog(
                    this
                )

            val root =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    setBackgroundColor(
                        Color.BLACK
                    )
                }

            val topBar =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        dp(8),
                        dp(6),
                        dp(8),
                        dp(6)
                    )
                }

            val title =
                TextView(this).apply {

                    text =
                        "${project.name} • " +
                            projectLabel(
                                project.type
                            )

                    setTextColor(
                        Color.WHITE
                    )

                    textSize =
                        14f

                    setPadding(
                        dp(8),
                        0,
                        dp(8),
                        0
                    )
                }

            val close =
                Button(this).apply {

                    text =
                        "CLOSE"
                }

            topBar.addView(
                title,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            topBar.addView(
                close,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(48)
                )
            )

            val webView =
                WebView(
                    this
                )

            webView.settings.apply {

                javaScriptEnabled =
                    true

                domStorageEnabled =
                    true

                mediaPlaybackRequiresUserGesture =
                    false

                /*
                 * Keep direct local filesystem access disabled.
                 */
                allowFileAccess =
                    false

                allowContentAccess =
                    false

                /*
                 * Avoid unnecessary WebView caching.
                 */
                cacheMode =
                    android.webkit.WebSettings.LOAD_NO_CACHE
            }

            webView.webViewClient =
                WebViewClient()

            webView.webChromeClient =
                WebChromeClient()

            root.addView(
                topBar
            )

            root.addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            dialog.setContentView(
                root
            )

            var destroyed =
                false

            fun destroyPreview() {

                if (destroyed) {
                    return
                }

                destroyed =
                    true

                try {

                    webView.stopLoading()

                    webView.loadUrl(
                        "about:blank"
                    )

                    webView.clearHistory()

                    webView.removeAllViews()

                    webView.destroy()

                } catch (
                    _: Exception
                ) {
                }
            }

            close.setOnClickListener {

                destroyPreview()

                dialog.dismiss()
            }

            dialog.setOnDismissListener {

                destroyPreview()
            }

            dialog.show()

            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            /*
             * Load only after the dialog is ready.
             */
            webView.loadDataWithBaseURL(
                "https://jarvis.local/",
                html,
                "text/html",
                "UTF-8",
                null
            )

            setStatus(
                "PREVIEW: ${projectLabel(project.type)}"
            )

        } catch (
            e: Exception
        ) {

            addJarvisMessage(
                "Preview couldn't open: " +
                    (
                        e.message ?:
                        "Unknown WebView error"
                    )
            )

            setStatus(
                "PREVIEW: ERROR"
            )
        }
    }

    // ============================================================
    // NORMAL LOCAL AI
    // ============================================================

    private fun generateAIResponse(
        message: String
    ) {

        if (generationRunning) {

            return
        }

        val model =
            llamaModel

        if (model == null) {

            modelLoaded =
                false

            setStatus(
                "SYSTEM: MODEL NOT LOADED"
            )

            return
        }

        generationRunning =
            true

        setBusy(
            true
        )

        setStatus(
            "JARVIS: THINKING LOCALLY"
        )

        lifecycleScope.launch {

            try {

                val remembered =
                    prefs.getString(
                        "previous_user_message",
                        ""
                    ).orEmpty()

                val memoryContext =
                    if (
                        remembered.isBlank()
                    ) {

                        ""

                    } else {

                        """
Previous user message:
$remembered
""".trimIndent()
                    }

                val prompt =
                    """
$memoryContext

Current user message:
$message
""".trimIndent()

                val result =
                    withContext(
                        Dispatchers.Default
                    ) {

                        Llama.complete(
                            model,
                            prompt =
                                prompt,
                            systemPrompt =
                                "You are JARVIS, a helpful, intelligent, " +
                                    "concise private AI assistant running locally " +
                                    "on the user's Android phone. " +
                                    "Answer naturally and directly. " +
                                    "Do not pretend you performed actions " +
                                    "you cannot perform.",
                            maxTokens =
                                224
                        )
                    }

                val answer =
                    result
                        .text
                        .trim()

                if (
                    answer.isBlank()
                ) {

                    addJarvisMessage(
                        "I couldn't generate a response."
                    )

                } else {

                    addJarvisMessage(
                        answer
                    )

                    speak(
                        answer
                    )
                }

                prefs
                    .edit()
                    .putString(
                        "previous_user_message",
                        message
                    )
                    .apply()

                setStatus(
                    "SYSTEM: AI READY"
                )

            } catch (
                e: Exception
            ) {

                addJarvisMessage(
                    "Local AI error: " +
                        (
                            e.message ?:
                            "Unknown model error"
                        )
                )

                setStatus(
                    "SYSTEM: AI ERROR"
                )

            } finally {

                generationRunning =
                    false

                setBusy(
                    false
                )
            }
        }
    }

    // ============================================================
    // CHAT + MEMORY
    // ============================================================

    private fun addUserMessage(
        message: String
    ) {

        appendChat(
            "\nYOU:\n$message\n"
        )
    }

    private fun addJarvisMessage(
        message: String
    ) {

        appendChat(
            "\nJARVIS:\n$message\n"
        )
    }

    private fun appendChat(
        message: String
    ) {

        if (
            !::chatText.isInitialized
        ) {

            return
        }

        chatText.append(
            message
        )
    }

    private fun rememberLastUserMessage(
        message: String
    ) {

        prefs
            .edit()
            .putString(
                "last_user_message",
                message
            )
            .apply()
    }

    private fun restoreMemory() {

        val previous =
            prefs.getString(
                "last_user_message",
                null
            )

        if (
            !previous.isNullOrBlank()
        ) {

            addJarvisMessage(
                "Local memory restored."
            )
        }
    }

    // ============================================================
    // VOICE
    // ============================================================

    private fun requestVoiceInput() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            startVoiceInput()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun startVoiceInput() {

        try {

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        Locale.getDefault()
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        "Talk to Jarvis"
                    )
                }

            setStatus(
                "JARVIS: LISTENING"
            )

            speechLauncher.launch(
                intent
            )

        } catch (
            _: Exception
        ) {

            setStatus(
                "VOICE INPUT UNAVAILABLE"
            )

            addJarvisMessage(
                "Voice recognition isn't available on this device."
            )
        }
    }

    // ============================================================
    // TEXT TO SPEECH
    // ============================================================

    override fun onInit(
        status: Int
    ) {

        if (
            status ==
            TextToSpeech.SUCCESS
        ) {

            tts?.language =
                Locale.getDefault()

            tts?.setSpeechRate(
                1.0f
            )
        }
    }

    private fun speak(
        text: String
    ) {

        try {

            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "jarvis_reply"
            )

        } catch (
            _: Exception
        ) {
        }
    }

    // ============================================================
    // MODEL
    // ============================================================

    private fun checkModel() {

        modelReady =
            modelFile.exists() &&
                modelFile.length() >
                100_000_000L

        if (modelReady) {

            setStatus(
                "SYSTEM: MODEL INSTALLED"
            )

            modelButton.text =
                "LOAD AI"

        } else {

            setStatus(
                "SYSTEM: READY — MODEL NOT INSTALLED"
            )

            modelButton.text =
                "MODEL"
        }
    }

    private fun loadLocalModel() {

        if (!modelReady) {

            addJarvisMessage(
                "The model needs to be downloaded first."
            )

            return
        }

        if (
            modelLoaded ||
            modelLoading
        ) {

            return
        }

        modelLoading =
            true

        modelButton.isEnabled =
            false

        sendButton.isEnabled =
            false

        buildButton.isEnabled =
            false

        previewButton.isEnabled =
            false

        setStatus(
            "SYSTEM: LOADING LOCAL AI"
        )

        addJarvisMessage(
            "Loading my local AI brain. " +
                "This can take a moment."
        )

        lifecycleScope.launch {

            try {

                val loadedModel =
                    withContext(
                        Dispatchers.Default
                    ) {

                        Llama.loadModel(
                            modelPath =
                                modelFile.absolutePath,
                            config =
                                LlamaConfig(

                                    /*
                                     * Reduced further from 3072.
                                     *
                                     * This is a major memory-safety change.
                                     */
                                    contextSize =
                                        2048,

                                    /*
                                     * Conservative CPU use helps reduce
                                     * heat and simultaneous memory pressure.
                                     */
                                    threads =
                                        Runtime
                                            .getRuntime()
                                            .availableProcessors()
                                            .coerceIn(
                                                2,
                                                4
                                            )
                                )
                        )
                    }

                llamaModel =
                    loadedModel

                modelLoaded =
                    true

                modelButton.text =
                    "AI ✓"

                setStatus(
                    "SYSTEM: AI READY"
                )

                addJarvisMessage(
                    "Local AI loaded successfully. " +
                        "Chat and Builder Mode are ready."
                )

            } catch (
                e: Exception
            ) {

                llamaModel =
                    null

                modelLoaded =
                    false

                modelButton.text =
                    "LOAD AI"

                setStatus(
                    "SYSTEM: MODEL LOAD ERROR"
                )

                addJarvisMessage(
                    "I couldn't load the local model: " +
                        (
                            e.message ?:
                            "Unknown error"
                        )
                )

            } finally {

                modelLoading =
                    false

                modelButton.isEnabled =
                    true

                sendButton.isEnabled =
                    true

                buildButton.isEnabled =
                    true

                previewButton.isEnabled =
                    true
            }
        }
    }

    // ============================================================
    // MODEL DOWNLOAD
    // ============================================================

    private fun downloadModel() {

        modelButton.isEnabled =
            false

        progressBar.visibility =
            View.VISIBLE

        progressBar.progress =
            0

        setStatus(
            "MODEL: CONNECTING"
        )

        addJarvisMessage(
            "Downloading my local AI model. " +
                "It only needs to be downloaded once."
        )

        downloadExecutor.execute {

            try {

                modelFile
                    .parentFile
                    ?.mkdirs()

                var downloaded =
                    if (
                        partialModelFile.exists()
                    ) {

                        partialModelFile.length()

                    } else {

                        0L
                    }

                var connection =
                    createModelConnection(
                        downloaded
                    )

                var responseCode =
                    connection.responseCode

                if (
                    downloaded > 0L &&
                    responseCode !=
                    HttpURLConnection.HTTP_PARTIAL
                ) {

                    connection.disconnect()

                    partialModelFile.delete()

                    downloaded =
                        0L

                    connection =
                        createModelConnection(
                            0L
                        )

                    responseCode =
                        connection.responseCode
                }

                if (
                    responseCode !=
                    HttpURLConnection.HTTP_OK &&
                    responseCode !=
                    HttpURLConnection.HTTP_PARTIAL
                ) {

                    connection.disconnect()

                    throw IllegalStateException(
                        "HTTP $responseCode"
                    )
                }

                val contentLength =
                    connection.contentLengthLong

                val totalBytes =
                    if (
                        responseCode ==
                            HttpURLConnection.HTTP_PARTIAL &&
                        contentLength >
                            0L
                    ) {

                        downloaded +
                            contentLength

                    } else {

                        contentLength
                    }

                connection
                    .inputStream
                    .use { input ->

                        RandomAccessFile(
                            partialModelFile,
                            "rw"
                        ).use { output ->

                            if (
                                responseCode ==
                                    HttpURLConnection.HTTP_PARTIAL &&
                                downloaded >
                                    0L
                            ) {

                                output.seek(
                                    downloaded
                                )

                            } else {

                                output.setLength(
                                    0L
                                )
                            }

                            /*
                             * Smaller buffer than before.
                             */
                            val buffer =
                                ByteArray(
                                    64 * 1024
                                )

                            var current =
                                downloaded

                            var lastUiUpdate =
                                0L

                            while (true) {

                                val bytesRead =
                                    input.read(
                                        buffer
                                    )

                                if (
                                    bytesRead <
                                    0
                                ) {

                                    break
                                }

                                output.write(
                                    buffer,
                                    0,
                                    bytesRead
                                )

                                current +=
                                    bytesRead

                                val now =
                                    System.currentTimeMillis()

                                if (
                                    now -
                                        lastUiUpdate >=
                                        350L
                                ) {

                                    lastUiUpdate =
                                        now

                                    val percent =
                                        if (
                                            totalBytes >
                                            0L
                                        ) {

                                            (
                                                current *
                                                    100L /
                                                    totalBytes
                                                )
                                                .toInt()
                                                .coerceIn(
                                                    0,
                                                    100
                                                )

                                        } else {

                                            0
                                        }

                                    runOnUiThread {

                                        progressBar.progress =
                                            percent

                                        setStatus(
                                            "MODEL: DOWNLOADING $percent%"
                                        )
                                    }
                                }
                            }
                        }
                    }

                connection.disconnect()

                if (
                    !partialModelFile.exists() ||
                    partialModelFile.length() <=
                    100_000_000L
                ) {

                    throw IllegalStateException(
                        "Downloaded model is incomplete"
                    )
                }

                if (
                    modelFile.exists()
                ) {

                    modelFile.delete()
                }

                val renamed =
                    partialModelFile.renameTo(
                        modelFile
                    )

                if (!renamed) {

                    partialModelFile.copyTo(
                        modelFile,
                        overwrite =
                            true
                    )

                    partialModelFile.delete()
                }

                runOnUiThread {

                    modelButton.isEnabled =
                        true

                    progressBar.progress =
                        100

                    progressBar.visibility =
                        View.GONE

                    checkModel()

                    addJarvisMessage(
                        "Local AI model downloaded successfully."
                    )

                    loadLocalModel()
                }

            } catch (
                e: Exception
            ) {

                runOnUiThread {

                    modelButton.isEnabled =
                        true

                    progressBar.visibility =
                        View.GONE

                    setStatus(
                        "MODEL: DOWNLOAD PAUSED"
                    )

                    addJarvisMessage(
                        "The model download stopped: " +
                            (
                                e.message ?:
                                "Network error"
                            ) +
                            ". The partial download was kept. " +
                            "Press MODEL to resume."
                    )
                }
            }
        }
    }

    private fun createModelConnection(
        downloaded: Long
    ): HttpURLConnection {

        return (
            URL(
                MODEL_URL
            )
                .openConnection()
                as HttpURLConnection
            ).apply {

                connectTimeout =
                    20_000

                readTimeout =
                    60_000

                instanceFollowRedirects =
                    true

                setRequestProperty(
                    "Accept-Encoding",
                    "identity"
                )

                setRequestProperty(
                    "User-Agent",
                    "Jarvis-Android"
                )

                if (
                    downloaded >
                    0L
                ) {

                    setRequestProperty(
                        "Range",
                        "bytes=$downloaded-"
                    )
                }

                connect()
            }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun projectLabel(
        type: JarvisProjectType
    ): String {

        return when (
            type
        ) {

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

    private fun setBusy(
        busy: Boolean
    ) {

        sendButton.isEnabled =
            !busy

        talkButton.isEnabled =
            !busy

        buildButton.isEnabled =
            !busy

        modelButton.isEnabled =
            !busy

        /*
         * Preview remains disabled during local inference.
         *
         * This avoids WebView + llama memory use at the same time.
         */
        previewButton.isEnabled =
            !busy
    }

    private fun setStatus(
        text: String
    ) {

        if (
            ::statusText.isInitialized
        ) {

            statusText.text =
                text
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            ).toInt()
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    override fun onDestroy() {

        try {

            tts?.stop()
            tts?.shutdown()

        } catch (
            _: Exception
        ) {
        }

        /*
         * Do not clear workspace build state here.
         *
         * If Android kills us while building, that state is
         * intentionally used for recovery on the next launch.
         */

        try {

            downloadExecutor.shutdownNow()

        } catch (
            _: Exception
        ) {
        }

        val model =
            llamaModel

        llamaModel =
            null

        modelLoaded =
            false

        modelLoading =
            false

        if (
            model != null
        ) {

            Thread {

                try {

                    kotlinx.coroutines
                        .runBlocking {

                            Llama.releaseModel(
                                model
                            )
                        }

                } catch (
                    _: Exception
                ) {
                }

            }.start()
        }

        super.onDestroy()
    }
}
