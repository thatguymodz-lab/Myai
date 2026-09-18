package com.novajarvis.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
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

    private var tts: TextToSpeech? = null

    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("jarvis_memory", MODE_PRIVATE)
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

    private var modelReady = false
    private var modelLoaded = false

    /*
     * Correct llama-android model type.
     */
    private var llamaModel: LlamaModel? = null

    // ============================================================
    // ACTIVITY RESULT / VOICE
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

                val spokenText = results?.firstOrNull()

                if (!spokenText.isNullOrBlank()) {

                    inputText.setText(spokenText)
                    inputText.setSelection(spokenText.length)

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

                setStatus("MICROPHONE PERMISSION DENIED")

                addJarvisMessage(
                    "I need microphone permission before TALK can work."
                )
            }
        }

    // ============================================================
    // CREATE
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        buildInterface()

        restoreMemory()

        checkModel()

        addJarvisMessage(
            "JARVIS Android initialized."
        )

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
    // USER INTERFACE
    // ============================================================

    private fun buildInterface() {

        val background = Color.rgb(3, 10, 18)
        val panel = Color.rgb(8, 20, 31)
        val cyan = Color.rgb(0, 217, 255)
        val white = Color.rgb(235, 248, 255)
        val muted = Color.rgb(130, 170, 185)

        val root =
            LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

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

                text = "J A R V I S"
                textSize = 28f

                setTextColor(cyan)

                gravity = Gravity.CENTER

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

                text = "PRIVATE ON-DEVICE AI"
                textSize = 12f

                setTextColor(muted)

                gravity = Gravity.CENTER

                setPadding(
                    0,
                    0,
                    0,
                    dp(12)
                )
            }

        statusText =
            TextView(this).apply {

                text = "SYSTEM: STARTING"
                textSize = 12f

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

                max = 100
                progress = 0
                visibility = View.GONE
            }

        val scroll =
            ScrollView(this).apply {

                isFillViewport = true
                setBackgroundColor(panel)
            }

        chatText =
            TextView(this).apply {

                textSize = 15f

                setTextColor(white)

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                setTextIsSelectable(true)
            }

        /*
         * FIX:
         * ScrollView inherits from FrameLayout, so use
         * FrameLayout.LayoutParams.
         */
        scroll.addView(
            chatText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        inputText =
            EditText(this).apply {

                hint = "Ask Jarvis..."

                setHintTextColor(muted)
                setTextColor(white)
                setBackgroundColor(panel)

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )

                maxLines = 4
            }

        val buttonRow =
            LinearLayout(this).apply {

                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
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

        buttonRow.addView(
            talkButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        buttonRow.addView(
            sendButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
        )

        buttonRow.addView(
            modelButton,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                marginStart = dp(6)
            }
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
                topMargin = dp(6)
            }
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {

                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        )

        root.addView(
            inputText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        root.addView(buttonRow)

        setContentView(root)

        sendButton.setOnClickListener {
            sendMessage()
        }

        talkButton.setOnClickListener {
            requestVoiceInput()
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

    private fun makeButton(
        label: String,
        textColor: Int,
        backgroundColor: Int
    ): Button {

        return Button(this).apply {

            text = label

            setTextColor(textColor)
            setBackgroundColor(backgroundColor)

            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            isAllCaps = false
        }
    }

    // ============================================================
    // CHAT
    // ============================================================

    private fun sendMessage() {

        val message =
            inputText.text
                .toString()
                .trim()

        if (message.isEmpty()) {
            return
        }

        inputText.setText("")

        addUserMessage(message)

        rememberLastUserMessage(message)

        if (!modelReady) {

            addJarvisMessage(
                "My local AI model hasn't been downloaded yet. " +
                    "Press MODEL first."
            )

            return
        }

        if (!modelLoaded) {

            addJarvisMessage(
                "I'm loading my local AI model. " +
                    "Please try again when the status says AI READY."
            )

            loadLocalModel()

            return
        }

        generateAIResponse(message)
    }

    // ============================================================
    // REAL LOCAL AI RESPONSE
    // ============================================================

    private fun generateAIResponse(message: String) {

        val model = llamaModel

        if (model == null) {

            modelLoaded = false

            setStatus(
                "SYSTEM: MODEL NOT LOADED"
            )

            return
        }

        sendButton.isEnabled = false
        talkButton.isEnabled = false

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
                            prompt = prompt,
                            systemPrompt =
                                "You are JARVIS, a helpful, intelligent, concise private AI assistant running locally on the user's Android phone. " +
                                    "Answer naturally and directly. " +
                                    "Do not pretend you performed actions you cannot perform.",
                            maxTokens = 256
                        )
                    }

                val answer =
                    result.text.trim()

                if (answer.isBlank()) {

                    addJarvisMessage(
                        "I couldn't generate a response."
                    )

                } else {

                    addJarvisMessage(answer)

                    speak(answer)
                }

                prefs.edit()
                    .putString(
                        "previous_user_message",
                        message
                    )
                    .apply()

                setStatus(
                    "SYSTEM: AI READY"
                )

            } catch (e: Exception) {

                addJarvisMessage(
                    "Local AI error: " +
                        (
                            e.message
                                ?: "Unknown model error"
                            )
                )

                setStatus(
                    "SYSTEM: AI ERROR"
                )

            } finally {

                sendButton.isEnabled = true
                talkButton.isEnabled = true
            }
        }
    }

    private fun addUserMessage(message: String) {

        appendChat(
            "\nYOU:\n$message\n"
        )
    }

    private fun addJarvisMessage(message: String) {

        appendChat(
            "\nJARVIS:\n$message\n"
        )
    }

    private fun appendChat(message: String) {

        chatText.append(message)
    }

    // ============================================================
    // LOCAL MEMORY
    // ============================================================

    private fun rememberLastUserMessage(message: String) {

        prefs.edit()
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
    // VOICE INPUT
    // ============================================================

    private fun requestVoiceInput() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
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

        } catch (e: Exception) {

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

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            tts?.language =
                Locale.getDefault()

            tts?.setSpeechRate(1.0f)
        }
    }

    private fun speak(text: String) {

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis_reply"
        )
    }

    // ============================================================
    // LOCAL LLAMA MODEL
    // ============================================================

    private fun checkModel() {

        modelReady =
            modelFile.exists() &&
                modelFile.length() > 100_000_000L

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

        if (modelLoaded) {
            return
        }

        modelButton.isEnabled = false
        sendButton.isEnabled = false

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
                                    contextSize = 2048,
                                    threads =
                                        Runtime
                                            .getRuntime()
                                            .availableProcessors()
                                            .coerceIn(
                                                2,
                                                6
                                            )
                                )
                        )
                    }

                /*
                 * loadedModel is LlamaModel.
                 */
                llamaModel = loadedModel

                modelLoaded = true

                modelButton.text = "AI ✓"

                setStatus(
                    "SYSTEM: AI READY"
                )

                addJarvisMessage(
                    "Local AI loaded successfully. I'm ready."
                )

            } catch (e: Exception) {

                llamaModel = null
                modelLoaded = false

                modelButton.text =
                    "LOAD AI"

                setStatus(
                    "SYSTEM: MODEL LOAD ERROR"
                )

                addJarvisMessage(
                    "I couldn't load the local model: " +
                        (
                            e.message
                                ?: "Unknown error"
                            )
                )

            } finally {

                modelButton.isEnabled = true
                sendButton.isEnabled = true
            }
        }
    }

    // ============================================================
    // MODEL DOWNLOADER
    // ============================================================

    private fun downloadModel() {

        modelButton.isEnabled = false

        progressBar.visibility =
            View.VISIBLE

        progressBar.progress = 0

        setStatus(
            "MODEL: CONNECTING"
        )

        addJarvisMessage(
            "Downloading my local AI model. " +
                "It's roughly 500 MB and only needs to be downloaded once."
        )

        downloadExecutor.execute {

            try {

                modelFile.parentFile?.mkdirs()

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

                /*
                 * If a partial file exists but the server does not
                 * support Range requests, restart cleanly.
                 */
                if (
                    downloaded > 0L &&
                    responseCode != HttpURLConnection.HTTP_PARTIAL
                ) {

                    connection.disconnect()

                    partialModelFile.delete()

                    downloaded = 0L

                    connection =
                        createModelConnection(0L)

                    responseCode =
                        connection.responseCode
                }

                if (
                    responseCode != HttpURLConnection.HTTP_OK &&
                    responseCode != HttpURLConnection.HTTP_PARTIAL
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
                        contentLength > 0L
                    ) {

                        downloaded + contentLength

                    } else {

                        contentLength
                    }

                connection.inputStream.use { input ->

                    RandomAccessFile(
                        partialModelFile,
                        "rw"
                    ).use { output ->

                        if (
                            responseCode ==
                            HttpURLConnection.HTTP_PARTIAL &&
                            downloaded > 0L
                        ) {

                            output.seek(downloaded)

                        } else {

                            output.setLength(0L)
                        }

                        val buffer =
                            ByteArray(
                                128 * 1024
                            )

                        var current =
                            downloaded

                        var lastUiUpdate =
                            0L

                        while (true) {

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

                            current += bytesRead

                            val now =
                                System.currentTimeMillis()

                            if (
                                now - lastUiUpdate >= 250L
                            ) {

                                lastUiUpdate = now

                                val percent =
                                    if (totalBytes > 0L) {

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
                    partialModelFile.length() <= 100_000_000L
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
                        overwrite = true
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

                    /*
                     * Automatically load the model when
                     * downloading finishes.
                     */
                    loadLocalModel()
                }

            } catch (e: Exception) {

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
                                e.message
                                    ?: "Network error"
                            ) +
                            ". The partial download has been kept. " +
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

                connectTimeout = 20_000
                readTimeout = 60_000

                instanceFollowRedirects = true

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

    private fun setStatus(text: String) {

        statusText.text = text
    }

    private fun dp(value: Int): Int {

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

        tts?.stop()
        tts?.shutdown()

        downloadExecutor.shutdownNow()

        val model = llamaModel

        if (model != null) {

            /*
             * releaseModel expects LlamaModel.
             */
            Thread {

                try {

                    kotlinx.coroutines.runBlocking {

                        Llama.releaseModel(model)
                    }

                } catch (_: Exception) {
                    // App is already closing.
                }

            }.start()
        }

        llamaModel = null
        modelLoaded = false

        super.onDestroy()
    }
}
