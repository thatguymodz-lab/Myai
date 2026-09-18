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
                JarvisProject
