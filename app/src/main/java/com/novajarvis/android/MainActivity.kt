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
    private var llamaModel: LlamaModel? = null

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

                val spoken = results?.firstOrNull()

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

        workspace = ProjectWorkspace(this)
        tts = TextToSpeech(this, this)

        buildInterface()
        restoreMemory()
        checkModel()

        addJarvisMessage(
            "JARVIS Android initialized."
        )

        workspace.getCurrentProject()?.let { current ->

            addJarvisMessage(
                "Project workspace restored: " +
                    "${current.name} (${projectLabel(current.type)})."
            )
       
