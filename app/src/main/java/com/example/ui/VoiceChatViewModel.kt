package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.voiceMessageDao()

    private val prefs = application.getSharedPreferences("voice_chat_settings", Context.MODE_PRIVATE)

    val messages: StateFlow<List<VoiceMessage>> = dao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var voiceManager: VoiceManager? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking

    private val _language = MutableStateFlow("ar")
    val language: StateFlow<String> = _language

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey

    private val _selectedModel = MutableStateFlow("gemini-3.5-flash")
    val selectedModel: StateFlow<String> = _selectedModel

    private val _isApiKeyValid = MutableStateFlow(true)
    val isApiKeyValid: StateFlow<Boolean> = _isApiKeyValid

    init {
        loadSettings()
        checkApiKey()
        initVoiceManager()
    }

    private fun loadSettings() {
        _customApiKey.value = prefs.getString("custom_api_key", "") ?: ""
        _selectedModel.value = prefs.getString("selected_model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
    }

    fun saveSettings(apiKey: String, model: String) {
        prefs.edit().apply {
            putString("custom_api_key", apiKey.trim())
            putString("selected_model", model)
            apply()
        }
        _customApiKey.value = apiKey.trim()
        _selectedModel.value = model
        checkApiKey()
    }

    private fun checkApiKey() {
        val activeKey = getActiveApiKey()
        _isApiKeyValid.value = activeKey.isNotEmpty() && activeKey != "MY_GEMINI_API_KEY"
    }

    private fun getActiveApiKey(): String {
        return _customApiKey.value.ifEmpty { BuildConfig.GEMINI_API_KEY }
    }

    private fun initVoiceManager() {
        voiceManager = VoiceManager(
            context = getApplication(),
            onSpeechResult = { text ->
                handleSpeechResult(text)
            },
            onError = { err ->
                _errorMessage.value = err
            }
        )

        // Sync local states from voiceManager
        viewModelScope.launch {
            voiceManager?.isListening?.collect { _isListening.value = it }
        }
        viewModelScope.launch {
            voiceManager?.isSpeaking?.collect { _isSpeaking.value = it }
        }
        viewModelScope.launch {
            voiceManager?.rmsDb?.collect { _rmsDb.value = it }
        }
        viewModelScope.launch {
            voiceManager?.language?.collect { _language.value = it }
        }
        viewModelScope.launch {
            voiceManager?.speechRate?.collect { _speechRate.value = it }
        }
    }

    fun toggleListening() {
        if (!isApiKeyValid.value) {
            _errorMessage.value = "الرجاء تكوين مفتاح API الخاص بـ Gemini أولاً في الإعدادات أو لوحة الأسرار (Secrets)."
            return
        }
        _errorMessage.value = null
        val manager = voiceManager ?: return
        if (manager.isListening.value) {
            manager.stopListening()
        } else {
            manager.startListening()
        }
    }

    fun stopSpeaking() {
        voiceManager?.stopSpeaking()
    }

    fun setLanguage(lang: String) {
        _language.value = lang
        voiceManager?.setLanguage(lang)
    }

    fun setSpeechRate(rate: Float) {
        _speechRate.value = rate
        voiceManager?.setSpeechRate(rate)
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAllMessages()
            withContext(Dispatchers.Main) {
                voiceManager?.stopSpeaking()
                _errorMessage.value = null
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private fun handleSpeechResult(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // 1. Insert user message to DB
            val userMsg = VoiceMessage(text = text, isUser = true)
            dao.insertMessage(userMsg)

            // 2. Query Gemini API
            queryGemini()
        }
    }

    private fun queryGemini() {
        viewModelScope.launch {
            _isThinking.value = true
            _errorMessage.value = null

            try {
                // Prepare conversation history
                val currentMessages = messages.value
                val historyContents = currentMessages.map { msg ->
                    Content(
                        parts = listOf(Part(text = msg.text))
                    )
                }

                val systemInstructionText = if (_language.value == "ar") {
                    "أنت مساعد صوتي ذكي وموجز ولطيف باللغة العربية. يجب أن تجيب دائماً باختصار شديد وبطريقة محادثة مناسبة للإلقاء الصوتي. تجنب كتابة القوائم أو التنسيقات المعقدة أو العلامات النجمية؛ اكتب نصوصاً عادية وقصيرة جداً لا تتعدى جملتين أو ثلاث جمل."
                } else {
                    "You are an intelligent, concise, and friendly AI Voice Assistant. Respond in English. Your responses must be extremely short, natural, and highly conversational, designed to be spoken aloud. Avoid lists, bullet points, or complex formatting; write short paragraphs of max 2-3 sentences."
                }

                val request = GenerateContentRequest(
                    contents = historyContents,
                    systemInstruction = Content(parts = listOf(Part(text = systemInstructionText)))
                )

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(
                        model = _selectedModel.value,
                        apiKey = getActiveApiKey(),
                        request = request
                    )
                }

                val replyText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!replyText.isNullOrBlank()) {
                    // 3. Save reply to DB
                    val aiMsg = VoiceMessage(text = replyText, isUser = false)
                    dao.insertMessage(aiMsg)

                    // 4. Speak reply out loud
                    voiceManager?.speak(replyText)
                } else {
                    _errorMessage.value = "لم أستطع الحصول على رد مناسب من خوادم الذكاء الاصطناعي."
                }
            } catch (e: Exception) {
                _errorMessage.value = "فشل الاتصال بـ Gemini: ${e.localizedMessage ?: "خطأ غير معروف"}"
            } finally {
                _isThinking.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager?.destroy()
    }
}
