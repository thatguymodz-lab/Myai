package com.novajarvis.android

import android.Manifest
import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var chatText: TextView
    private lateinit var inputText: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sendButton: Button
    private lateinit var talkButton: Button
    private lateinit var modelButton: Button

    private var tts: TextToSpeech? = null

    private val executor = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("jarvis_memory", MODE_PRIVATE)
    }

    /*
     * Model location.
     *
     * IMPORTANT:
     * Replace MODEL_URL later with the direct HTTPS URL of the GGUF
     * model we decide to use.
     */
    private val modelFile by lazy {
        File(filesDir, "models/jarvis.gguf")
    }

    private val partialModelFile by lazy {
        File(filesDir, "models/jarvis.gguf.part")
    }

    private val MODEL_URL =
        "https://example.com/jarvis.gguf"

    private var modelReady = false

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
                setStatus("Microphone permission denied")
                addJarvisMessage(
                    "I need microphone permission before TALK can work."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        buildInterface()

        restoreMemory()

        checkModel()

        addJarvisMessage(
            "JARVIS Android initialized.\n" +
                "Text chat, TALK, speech output and local memory are ready."
        )
    }

    /*
     * ------------------------------------------------------------
     * USER INTERFACE
     * ------------------------------------------------------------
     */

    private fun buildInterface() {

        val background = Color.rgb(3, 10, 18)
        val panel = Color.rgb(8, 20, 31)
        val cyan = Color.rgb(0, 217, 255)
        val white = Color.rgb(235, 248, 255)
        val muted = Color.rgb(130, 170, 185)

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)

            setPadding(
                dp(16),
                dp(20),
                dp(16),
                dp(16)
            )
        }

        val title = TextView(this).apply {

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

        val subtitle = TextView(this).apply {

            text = "LOCAL AI ASSISTANT"

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

        statusText = TextView(this).apply {

            text = "SYSTEM: STARTING"

            textSize = 12f

            setTextColor(cyan)

            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
            )

            setBackgroundColor(panel)
        }

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {

            max = 100

            progress = 0

            visibility = View.GONE
        }

        val scroll = ScrollView(this).apply {

            isFillViewport = true

            setBackgroundColor(panel)
        }

        chatText = TextView(this).apply {

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

        scroll.addView(
            chatText,
            ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            )
        )

        inputText = EditText(this).apply {

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

        val buttonRow = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER
        }

        talkButton = makeButton(
            "TALK",
            cyan,
            background
        )

        sendButton = makeButton(
            "SEND",
            cyan,
            background
        )

        modelButton = makeButton(
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

            if (modelReady) {

                addJarvisMessage(
                    "The local AI model is already installed."
                )

            } else {

                downloadModel()
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

    /*
     * ------------------------------------------------------------
     * CHAT
     * ------------------------------------------------------------
     */

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

        setStatus("JARVIS: THINKING")

        executor.execute {

            val response =
                generateResponse(message)

            runOnUiThread {

                addJarvisMessage(response)

                speak(response)

                setStatus(
                    if (modelReady)
                        "SYSTEM: LOCAL MODEL READY"
                    else
                        "SYSTEM: READY"
                )
            }
        }
    }

    /*
     * This is deliberately separated from the UI.
     *
     * Step 9 will connect this method to the llama Android
     * inference library and the GGUF model.
     */
    private fun generateResponse(
        message: String
    ): String {

        val lower =
            message.lowercase(Locale.getDefault())

        return when {

            lower == "hello" ||
                lower == "hi" ||
                lower.contains("hello jarvis") -> {

                "Hello. Jarvis is online."
            }

            lower.contains("what do you remember") -> {

                val remembered =
                    prefs.getString(
                        "last_user_message",
                        null
                    )

                if (remembered.isNullOrBlank()) {

                    "My local memory is currently empty."

                } else {

                    "The last thing I remember you saying is: $remembered"
                }
            }

            lower.contains("clear memory") -> {

                prefs.edit()
                    .clear()
                    .apply()

                "Local memory cleared."
            }

            modelReady -> {

                /*
                 * The model exists, but actual llama inference
                 * is connected in the next stage.
                 */
                "The local model is installed. " +
                    "The inference engine will be connected in the next step."
            }

            else -> {

                "I heard you. My interface, voice and memory are working. " +
                    "Install the local model with the MODEL button " +
                    "to prepare offline AI."
            }
        }
    }

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

        chatText.append(message)
    }

    /*
     * ------------------------------------------------------------
     * MEMORY
     * ------------------------------------------------------------
     */

    private fun rememberLastUserMessage(
        message: String
    ) {

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

    /*
     * ------------------------------------------------------------
     * VOICE INPUT
     * ------------------------------------------------------------
     */

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

            setStatus("JARVIS: LISTENING")

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

    /*
     * ------------------------------------------------------------
     * TEXT TO SPEECH
     * ------------------------------------------------------------
     */

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

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis_reply"
        )
    }

    /*
     * ------------------------------------------------------------
     * MODEL
     * ------------------------------------------------------------
     */

    private fun checkModel() {

        modelReady =
            modelFile.exists() &&
            modelFile.length() > 0L

        if (modelReady) {

            setStatus(
                "SYSTEM: LOCAL MODEL READY"
            )

            modelButton.text =
                "MODEL ✓"

        } else {

            setStatus(
                "SYSTEM: READY — MODEL NOT INSTALLED"
            )

            modelButton.text =
                "MODEL"
        }
    }

    /*
     * Resumable downloader.
     *
     * If Android loses connection, the partial .part file remains.
     * Pressing MODEL again attempts to resume from that byte.
     */
    private fun downloadModel() {

        if (
            MODEL_URL.contains(
                "example.com"
            )
        ) {

            addJarvisMessage(
                "The downloader is ready, but the real GGUF model URL " +
                    "still needs to be configured."
            )

            return
        }

        modelButton.isEnabled =
            false

        progressBar.visibility =
            View.VISIBLE

        setStatus(
            "MODEL: PREPARING DOWNLOAD"
        )

        executor.execute {

            try {

                modelFile.parentFile
                    ?.mkdirs()

                var downloaded =
                    if (partialModelFile.exists())
                        partialModelFile.length()
                    else
                        0L

                val connection =
                    URL(MODEL_URL)
                        .openConnection()
                            as HttpURLConnection

                connection.connectTimeout =
                    15000

                connection.readTimeout =
                    30000

                connection.setRequestProperty(
                    "Accept-Encoding",
                    "identity"
                )

                if (downloaded > 0L) {

                    connection.setRequestProperty(
                        "Range",
                        "bytes=$downloaded-"
                    )
                }

                connection.connect()

                val responseCode =
                    connection.responseCode

                /*
                 * 206 = server accepted resume.
                 * 200 = server sent the complete file.
                 *
                 * If resume was requested but the server returns 200,
                 * restart cleanly so we don't append a duplicate file.
                 */
                if (
                    downloaded > 0L &&
                    responseCode !=
                    HttpURLConnection.HTTP_PARTIAL
                ) {

                    partialModelFile.delete()

                    downloaded =
                        0L
                }

                val contentLength =
                    connection.contentLengthLong

                val totalBytes =
                    if (
                        responseCode ==
                        HttpURLConnection.HTTP_PARTIAL
                    ) {

                        downloaded +
                            contentLength

                    } else {

                        contentLength
                    }

                val input =
                    connection.inputStream

                val output =
                    RandomAccessFile(
                        partialModelFile,
                        "rw"
                    )

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
                        1024 * 128
                    )

                var bytesRead: Int

                var current =
                    downloaded

                var lastUiUpdate =
                    0L

                while (
                    input.read(buffer)
                        .also {
                            bytesRead = it
                        } != -1
                ) {

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
                        lastUiUpdate >
                        250
                    ) {

                        lastUiUpdate =
                            now

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

                output.close()

                input.close()

                connection.disconnect()

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

                    throw IllegalStateException(
                        "Could not finalize model file"
                    )
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
                        "Local AI model download complete."
                    )
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
                        "The model download stopped. " +
                            "Your partial download was kept. " +
                            "Press MODEL to retry and resume."
                    )
                }
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * HELPERS
     * ------------------------------------------------------------
     */

    private fun setStatus(
        text: String
    ) {

        statusText.text =
            text
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
            resources.displayMetrics.density
        ).toInt()
    }

    override fun onDestroy() {

        tts?.stop()

        tts?.shutdown()

        executor.shutdownNow()

        super.onDestroy()
    }
}
