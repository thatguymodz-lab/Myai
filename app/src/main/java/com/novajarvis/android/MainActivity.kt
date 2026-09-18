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
import android.webkit.RenderProcessGoneDetail
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

    /*
     * Only one Preview is allowed to exist at a time.
     *
     * WebView can use a significant amount of memory. Keeping an old
     * WebView alive while running local Llama inference is one of the
     * easiest ways to push a phone over its memory limit.
     */
    private var previewDialog: Dialog? = null
    private var previewWebView: WebView? = null

    /*
     * Prevent callbacks from trying to update an Activity that Android
     * has already destroyed.
     */
    @Volatile
    private var activityDestroyed = false

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
                    inputText.setSelection(spoken.length)

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

        super.onCreate(savedInstanceState)

        activityDestroyed = false

        workspace =
            ProjectWorkspace(this)

        tts =
            TextToSpeech(
                this,
                this
            )

        buildInterface()

        restoreMemory()

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

        if (!workspace.wasBuildInterrupted()) {
            return
        }

        val interrupted =
            workspace.getInterruptedProject()

        /*
         * We have now started normally again, so the previous marker
         * can be consumed.
         */
        workspace.clearInterruptedBuild()

        if (interrupted == null) {

            addJarvisMessage(
                "I detected an interrupted build. " +
                    "Your last verified Preview was kept safe."
            )

            return
        }

        val html =
            workspace.readMainFile(
                interrupted
            )

        if (!html.isNullOrBlank()) {

            val problems =
                workspace.validateProject(
                    interrupted,
                    html
                )

            val fatal =
                problems.any {
                    workspace.isFatalValidationProblem(it)
                }

            if (!fatal) {

                workspace.markProjectVerified(
                    interrupted
                )

                addJarvisMessage(
                    "I recovered ${interrupted.name}. " +
                        "Its saved project passed validation and is ready to preview."
                )

                return
            }
        }

        addJarvisMessage(
            "The previous build of ${interrupted.name} was interrupted " +
                "before a complete project was saved. " +
                "The last verified Preview was kept safe."
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

                setBackgroundColor(background)

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

                setTextColor(cyan)

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

                setTextColor(muted)

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

                setTextColor(cyan)

                setBackgroundColor(panel)

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

                setBackgroundColor(panel)
            }

        chatText =
            TextView(this).apply {

                textSize =
                    15f

                setTextColor(white)

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                setTextIsSelectable(true)
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

                setHintTextColor(muted)
                setTextColor(white)
                setBackgroundColor(panel)

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

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)

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

        root.addView(normalRow)

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

        setContentView(root)

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

                modelLoaded -> {

                    addJarvisMessage(
                        "The local Jarvis AI model is loaded and ready."
                    )
                }

                modelReady -> {
                    loadLocalModel()
                }

                else -> {
                    downloadModel()
                }
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

            setTextColor(textColor)

            setBackgroundColor(backgroundColor)

            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            isAllCaps =
                false
        }
    }

    private fun updateBuilderButton() {

        if (!::buildButton.isInitialized) {
            return
        }

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

        inputText.setText("")

        addUserMessage(message)

        rememberLastUserMessage(message)

        val isNewBuild =
            workspace.isNewBuildRequest(
                message
            )

        val isEdit =
            workspace.isProjectEditRequest(
                message
            )

        val builderRequest =
            builderMode ||
                isNewBuild ||
                isEdit

        if (builderRequest) {

            val explicitType =
                workspace.detectProjectType(
                    message
                )

            val instantGame =
                isNewBuild &&
                    (
                        explicitType ==
                            JarvisProjectType.GAME_2D ||
                        explicitType ==
                            JarvisProjectType.GAME_3D
                    )

            if (instantGame) {

                handleBuilderRequest(message)
                return
            }
        }

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

        if (builderRequest) {

            handleBuilderRequest(message)

        } else {

            generateAIResponse(message)
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

            if (isGameProject(project)) {

                setStatus(
                    "BUILDER: GAME READY"
                )

                addJarvisMessage(
                    "${project.name} is ready as a " +
                        "${projectLabel(project.type)}. " +
                        "I created a verified playable foundation instantly. " +
                        "Press PREVIEW to play it, then tell me what you want changed."
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

        if (isGameProject(project)) {

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
     * Website generation gets enough room for compact HTML.
     *
     * Game edits are intentionally kept smaller because the working
     * foundation is already included in the prompt.
     */
    private fun builderTokenLimit(
        project: JarvisProject
    ): Int {

        return when (project.type) {

            JarvisProjectType.WEBSITE ->
                1152

            JarvisProjectType.GAME_2D ->
                288

            JarvisProjectType.GAME_3D ->
                288

            JarvisProjectType.UNKNOWN ->
                224
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

        val model =
            llamaModel

        if (model == null) {

            modelLoaded =
                false

            setStatus(
                "SYSTEM: MODEL NOT LOADED"
            )

            addJarvisMessage(
                "The local AI model needs to be loaded before I can build this."
            )

            if (modelReady) {
                loadLocalModel()
            }

            return
        }

        /*
         * Destroy Preview before inference begins.
         *
         * This releases WebView memory before Llama starts allocating
         * its generation buffers.
         */
        closeActivePreview()

        generationRunning =
            true

        setBusy(true)

        workspace.beginBuild(project)

        setStatus(
            if (editing) {

                "BUILDER: UPDATING " +
                    projectLabel(project.type)

            } else {

                "BUILDER: CREATING " +
                    projectLabel(project.type)
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
Create ONE complete compact index.html.
Request: $userRequest
Build the minimum working page first.
Mobile friendly.
Use concise HTML/CSS and minimal JavaScript.
No frameworks, base64 or large assets.
Include a viewport meta tag.
Always finish </body> and </html>.
Return only HTML inside <JARVIS_FILE> tags.
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
                                builderTokenLimit(project)
                        )
                    }

                if (activityDestroyed) {
                    return@launch
                }

                val raw =
                    result
                        .text
                        .trim()

                val html =
                    workspace.extractGeneratedHtml(
                        raw
                    )

                if (html.isNullOrBlank()) {

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
                 * Reject obviously truncated output before doing any save.
                 */
                if (
                    !html.contains(
                        "</body>",
                        ignoreCase = true
                    ) ||
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
                        workspace.isFatalValidationProblem(it)
                    }

                if (fatal) {

                    addJarvisMessage(
                        "The generated project didn't pass validation: " +
                            problems.joinToString("; ") +
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
                 * File work is kept off the UI thread.
                 */
                withContext(
                    Dispatchers.IO
                ) {

                    workspace.saveMainFileAtomic(
                        project,
                        html,
                        makePreviewable = true
                    )
                }

                successful =
                    true

                if (!activityDestroyed) {

                    addJarvisMessage(
                        "${project.name} is built, verified and saved. " +
                            "PREVIEW now points to this " +
                            "${projectLabel(project.type)}."
                    )

                    setStatus(
                        "BUILDER: PROJECT READY"
                    )

                    builderMode =
                        true

                    updateBuilderButton()
                }

            } catch (
                e: Exception
            ) {

                if (!activityDestroyed) {

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
                }

            } finally {

                /*
                 * If Android destroys the Activity during generation,
                 * leave the persisted BUILD_IN_PROGRESS marker alone.
                 * The next launch can then recover it correctly.
                 */
                if (!activityDestroyed) {

                    workspace.finishBuild(
                        project,
                        successful
                    )
                }

                generationRunning =
                    false

                if (!activityDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    // ============================================================
    // VERIFIED PREVIEW
    // ============================================================

    private fun previewVerifiedProject() {

        if (generationRunning) {

            addJarvisMessage(
                "Wait until the current build finishes before opening Preview."
            )

            setStatus(
                "PREVIEW: BUILD RUNNING"
            )

            return
        }

        val current =
            workspace.getCurrentProject()

        val preview =
            workspace.getPreviewProject()

        if (
            current != null &&
            (
                preview == null ||
                    preview.id != current.id
                )
        ) {

            val label =
                projectLabel(
                    current.type
                ).lowercase(
                    Locale.getDefault()
                )

            addJarvisMessage(
                "${current.name} doesn't have a verified $label build yet. " +
                    "Build it successfully first, then PREVIEW will open that $label."
            )

            setStatus(
                "PREVIEW: CURRENT PROJECT NOT VERIFIED"
            )

            return
        }

        val project =
            preview

        if (project == null) {

            addJarvisMessage(
                "There isn't a verified project to preview yet."
            )

            setStatus(
                "PREVIEW: NOTHING VERIFIED"
            )

            return
        }

        val html =
            workspace.readMainFile(
                project
            )

        if (html.isNullOrBlank()) {

            addJarvisMessage(
                "${project.name} doesn't have a readable verified build."
            )

            setStatus(
                "PREVIEW: FILE ERROR"
            )

            return
        }

        val fatal =
            workspace
                .validateProject(
                    project,
                    html
                )
                .any {
                    workspace.isFatalValidationProblem(it)
                }

        if (fatal) {

            addJarvisMessage(
                "${project.name}'s saved build failed validation, " +
                    "so Preview was blocked."
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

        if (activityDestroyed) {
            return
        }

        /*
         * Never keep two WebViews alive.
         */
        closeActivePreview()

        try {

            val dialog =
                Dialog(this)

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
                            projectLabel(project.type)

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
                WebView(this)

            previewDialog =
                dialog

            previewWebView =
                webView

            webView.settings.apply {

                javaScriptEnabled =
                    true

                domStorageEnabled =
                    true

                mediaPlaybackRequiresUserGesture =
                    false

                /*
                 * Generated pages are loaded from memory.
                 * They do not need direct local filesystem access.
                 */
                allowFileAccess =
                    false

                allowContentAccess =
                    false

                cacheMode =
                    android.webkit.WebSettings.LOAD_NO_CACHE
            }

            webView.webViewClient =
                object : WebViewClient() {

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {

                        /*
                         * WebView renderer crashed or Android reclaimed it.
                         * Handle it rather than allowing the Activity to crash.
                         */
                        if (!activityDestroyed) {

                            addJarvisMessage(
                                "Preview's web renderer stopped. " +
                                    "The project itself is still saved safely."
                            )

                            setStatus(
                                "PREVIEW: RENDERER RESTART NEEDED"
                            )
                        }

                        closeActivePreview()

                        return true
                    }
                }

            webView.webChromeClient =
                WebChromeClient()

            root.addView(topBar)

            root.addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            dialog.setContentView(root)

            close.setOnClickListener {
                closeActivePreview()
            }

            dialog.setOnDismissListener {

                if (
                    previewDialog ===
                    dialog
                ) {

                    destroyPreviewWebView(
                        webView
                    )

                    previewWebView =
                        null

                    previewDialog =
                        null
                }
            }

            dialog.show()

            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            /*
             * HTTPS base URL allows generated 3D projects to request
             * HTTPS CDN resources without pretending the page is a file.
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

            closeActivePreview()

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

    private fun destroyPreviewWebView(
        webView: WebView?
    ) {

        if (webView == null) {
            return
        }

        try {
            webView.stopLoading()
        } catch (
            _: Exception
        ) {
        }

        try {
            webView.loadUrl(
                "about:blank"
            )
        } catch (
            _: Exception
        ) {
        }

        try {
            webView.webChromeClient =
                null
        } catch (
            _: Exception
        ) {
        }

        try {
            webView.webViewClient =
                WebViewClient()
        } catch (
            _: Exception
        ) {
        }

        try {
            webView.clearHistory()
        } catch (
            _: Exception
        ) {
        }

        try {
            webView.removeAllViews()
        } catch (
            _: Exception
        ) {
        }

        try {
            webView.destroy()
        } catch (
            _: Exception
        ) {
        }
    }

    private fun closeActivePreview() {

        val dialog =
            previewDialog

        val webView =
            previewWebView

        previewDialog =
            null

        previewWebView =
            null

        destroyPreviewWebView(
            webView
        )

        if (
            dialog != null &&
            dialog.isShowing
        ) {

            try {
                dialog.dismiss()
            } catch (
                _: Exception
            ) {
            }
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

        /*
         * Free Preview/WebView memory before local inference.
         */
        closeActivePreview()

        generationRunning =
            true

        setBusy(true)

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
                    if (remembered.isBlank()) {

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

                if (activityDestroyed) {
                    return@launch
                }

                val answer =
                    result
                        .text
                        .trim()

                if (answer.isBlank()) {

                    addJarvisMessage(
                        "I couldn't generate a response."
                    )

                } else {

                    addJarvisMessage(answer)
                    speak(answer)
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

                if (!activityDestroyed) {

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
                }

            } finally {

                generationRunning =
                    false

                if (!activityDestroyed) {
                    setBusy(false)
                }
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
            activityDestroyed ||
            !::chatText.isInitialized
        ) {
            return
        }

        chatText.append(message)
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

        if (!previous.isNullOrBlank()) {

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

            speechLauncher.launch(intent)

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

        if (activityDestroyed) {
            return
        }

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
            modelLoading ||
            activityDestroyed
        ) {
            return
        }

        /*
         * Do not hold a WebView while loading the model.
         */
        closeActivePreview()

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
            "Loading my local AI brain. This can take a moment."
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
                                     * 2048 remains deliberately conservative
                                     * for phone stability.
                                     */
                                    contextSize =
                                        2048,

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

                if (activityDestroyed) {

                    try {

                        withContext(
                            Dispatchers.Default
                        ) {

                            Llama.releaseModel(
                                loadedModel
                            )
                        }

                    } catch (
                        _: Exception
                    ) {
                    }

                    return@launch
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

                if (!activityDestroyed) {

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
                }

            } finally {

                modelLoading =
                    false

                if (!activityDestroyed) {

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
    }

    // ============================================================
    // MODEL DOWNLOAD
    // ============================================================

    private fun downloadModel() {

        if (activityDestroyed) {
            return
        }

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
                    if (partialModelFile.exists()) {

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

                                output.seek(downloaded)

                            } else {

                                output.setLength(0L)
                            }

                            val buffer =
                                ByteArray(
                                    64 * 1024
                                )

                            var current =
                                downloaded

                            var lastUiUpdate =
                                0L

                            while (
                                !activityDestroyed &&
                                !Thread.currentThread().isInterrupted
                            ) {

                                val bytesRead =
                                    input.read(buffer)

                                if (bytesRead < 0) {
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

                                        if (!activityDestroyed) {

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
                    }

                connection.disconnect()

                if (activityDestroyed) {
                    return@execute
                }

                if (
                    !partialModelFile.exists() ||
                    partialModelFile.length() <=
                        100_000_000L
                ) {

                    throw IllegalStateException(
                        "Downloaded model is incomplete"
                    )
                }

                if (modelFile.exists()) {
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

                    if (activityDestroyed) {
                        return@runOnUiThread
                    }

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

                if (activityDestroyed) {
                    return@execute
                }

                runOnUiThread {

                    if (activityDestroyed) {
                        return@runOnUiThread
                    }

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
            URL(MODEL_URL)
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

            if (downloaded > 0L) {

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

    private fun setBusy(
        busy: Boolean
    ) {

        if (
            activityDestroyed ||
            !::sendButton.isInitialized
        ) {
            return
        }

        sendButton.isEnabled =
            !busy

        talkButton.isEnabled =
            !busy

        buildButton.isEnabled =
            !busy

        modelButton.isEnabled =
            !busy

        previewButton.isEnabled =
            !busy
    }

    private fun setStatus(
        text: String
    ) {

        if (
            !activityDestroyed &&
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

        /*
         * Set this first so background callbacks know not to touch UI.
         */
        activityDestroyed =
            true

        /*
         * WebView is deliberately destroyed before releasing the
         * local model to reduce simultaneous cleanup pressure.
         */
        closeActivePreview()

        try {

            tts?.stop()
            tts?.shutdown()

        } catch (
            _: Exception
        ) {
        }

        tts =
            null

        /*
         * Do NOT clear workspace build state here.
         *
         * If Android kills the Activity while generation is active,
         * ProjectWorkspace can detect the persisted marker next launch.
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

        /*
         * Releasing the native model can take time.
         * Never block Android's main/UI thread waiting for it.
         */
        if (model != null) {

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

            }.apply {

                name =
                    "JarvisModelRelease"

                isDaemon =
                    true

                start()
            }
        }

        super.onDestroy()
    }
}
