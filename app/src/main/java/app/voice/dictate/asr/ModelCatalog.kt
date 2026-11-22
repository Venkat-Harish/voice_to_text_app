package app.voice.dictate.asr

// Every speech-to-text engine and read-aloud voice the app can use. Ported from the web prototype's
// src/models/catalog.js: the same pinned Hugging Face revisions and exact byte sizes (used for progress and
// to verify downloads). Accuracy is English WER from Handy's benchmark (FLEURS, lower is better):
// https://models.handy.computer/

enum class EngineKind { NEMOTRON, PARAKEET, MOONSHINE, SYSTEM, KITTEN, KOKORO, SYSTEM_TTS }

/** SPEECH = speech to text (dictation); VOICE = text to speech (read aloud). */
enum class ModelKind { SPEECH, VOICE }

/** A file to download. [url] overrides the model's base URL (the pronunciation dictionaries live on GitHub). */
data class ModelFile(val name: String, val size: Long, val url: String? = null)

/** A read-aloud voice. [prior] scales Kitten's speed per voice (from its kitten_config.json). */
data class Voice(val id: String, val label: String, val gender: String, val accent: String = "US", val prior: Float = 1f)

data class Language(val value: String, val label: String, val promptId: Int)

data class NemotronConfig(
    val layers: Int = 24,
    val dModel: Int = 1024,
    val timeCacheFrames: Int = 8,
    val predLayers: Int = 2,
    val predHidden: Int = 640,
    val channelCacheFrames: Int,
    val prompts: Map<String, Int>?,
    val defaultLanguage: String = "en-US",
)

data class SpeechModel(
    val id: String,
    val name: String,
    val vendor: String,
    val params: String,
    val engine: EngineKind,
    val live: Boolean,
    val languages: String,
    val multilingual: Boolean = false,
    val wer: Double?,
    val license: String,
    val blurb: String,
    val runsOn: String,
    val heavy: Boolean = false,
    val base: String = "",
    val files: List<ModelFile> = emptyList(),
    val nemotron: NemotronConfig? = null,
    val source: String? = null,
    val kind: ModelKind = ModelKind.SPEECH,
    val voices: List<Voice> = emptyList(),
    val recommended: Boolean = false,
) {
    val revision: String get() = base.substringAfterLast('/').take(12)
    val totalBytes: Long get() = files.sumOf { it.size }
    val needsDownload: Boolean get() = engine != EngineKind.SYSTEM && engine != EngineKind.SYSTEM_TTS
    fun url(file: ModelFile) = file.url ?: "$base/${file.name}"
    /** The voice with this id, or the model's first (default) voice. */
    fun voice(id: String?): Voice? = voices.firstOrNull { it.id == id } ?: voices.firstOrNull()
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"

    const val SYSTEM_ID = "system"

    // Nemotron 3.5 language prompts (from the encoder's prompt_dictionary metadata).
    val LANGUAGES = listOf(
        Language("auto", "Auto-detect", 101),
        Language("en-US", "English (US)", 0),
        Language("en-GB", "English (UK)", 1),
        Language("hi-IN", "Hindi", 6),
        Language("es-ES", "Spanish (Spain)", 2),
        Language("es-US", "Spanish (US)", 3),
        Language("fr-FR", "French", 8),
        Language("fr-CA", "French (Canada)", 100),
        Language("de-DE", "German", 9),
        Language("it-IT", "Italian", 15),
        Language("pt-BR", "Portuguese (Brazil)", 12),
        Language("pt-PT", "Portuguese (Portugal)", 13),
        Language("nl-NL", "Dutch", 16),
        Language("ru-RU", "Russian", 11),
        Language("uk-UA", "Ukrainian", 19),
        Language("tr-TR", "Turkish", 18),
        Language("ar-AR", "Arabic", 7),
        Language("ja-JP", "Japanese", 10),
        Language("ko-KR", "Korean", 14),
        Language("vi-VN", "Vietnamese", 33),
        Language("zh-CN", "Chinese (Mandarin)", 4),
        Language("pl-PL", "Polish", 17),
        Language("sv-SE", "Swedish", 24),
        Language("cs-CZ", "Czech", 22),
        Language("da-DK", "Danish", 25),
        Language("fi-FI", "Finnish", 26),
        Language("nb-NO", "Norwegian", 103),
        Language("ro-RO", "Romanian", 20),
        Language("hu-HU", "Hungarian", 23),
        Language("bg-BG", "Bulgarian", 30),
        Language("hr-HR", "Croatian", 29),
        Language("et-EE", "Estonian", 60),
    )

    val MODELS = listOf(
        SpeechModel(
            id = SYSTEM_ID,
            name = "Android built-in",
            vendor = "Your phone",
            params = "no download",
            engine = EngineKind.SYSTEM,
            live = true,
            languages = "Many (phone settings)",
            wer = null,
            license = "Provided by Android",
            blurb = "Works straight away with no download. Uses the phone's own speech recognizer, on the device when the phone supports it. A good start while a model downloads, and the lightest option.",
            runsOn = "Phone's recognizer",
        ),
        SpeechModel(
            id = "nemotron-en",
            name = "Nemotron Streaming EN",
            vendor = "NVIDIA",
            params = "0.6B",
            engine = EngineKind.NEMOTRON,
            live = true,
            languages = "English",
            wer = 6.4,
            license = "NVIDIA Open Model License",
            blurb = "True word-by-word streaming with punctuation and capitals. The best live English option. Needs a recent flagship phone to keep up as you speak.",
            runsOn = "Flagship CPU",
            base = "$HF/mja00/nemotron-speech-streaming-en-0.6b-webgpu/resolve/1a8ebba8c1ea3a074b1748729ab5816a53d140ab",
            files = listOf(
                ModelFile("encoder.q4.onnx", 42083096),
                ModelFile("encoder.q4.data", 637898752),
                ModelFile("decoder.onnx", 28883664),
                ModelFile("joiner.onnx", 6894757),
                ModelFile("tokens.txt", 8952),
            ),
            nemotron = NemotronConfig(channelCacheFrames = 70, prompts = null),
            source = "$HF/nvidia/nemotron-speech-streaming-en-0.6b",
        ),
        SpeechModel(
            id = "nemotron-35",
            name = "Nemotron 3.5 Streaming",
            vendor = "NVIDIA",
            params = "0.6B",
            engine = EngineKind.NEMOTRON,
            live = true,
            languages = "40 languages incl. Hindi",
            multilingual = true,
            wer = 7.9,
            license = "OpenMDW 1.1",
            blurb = "Live streaming in 40 languages (pick one in Settings, or auto-detect). Heavier than the English model, so it needs a strong phone to keep up live.",
            runsOn = "Flagship CPU",
            heavy = true,
            base = "$HF/mja00/nemotron-3.5-asr-streaming-0.6b-webgpu/resolve/a46ddaccbd69adbab4b40b31d9a35acf8b8eaede",
            files = listOf(
                ModelFile("encoder.q4.onnx", 42296587),
                ModelFile("encoder.q4.data", 640696320),
                ModelFile("decoder.onnx", 59764944),
                ModelFile("joiner.onnx", 37824291),
                ModelFile("tokens.txt", 131440),
            ),
            nemotron = NemotronConfig(
                channelCacheFrames = 56,
                prompts = LANGUAGES.associate { it.value to it.promptId },
            ),
            source = "$HF/nvidia/nemotron-3.5-asr-streaming-0.6b",
        ),
        SpeechModel(
            id = "parakeet-unified",
            name = "Parakeet Unified EN",
            vendor = "NVIDIA",
            params = "0.6B",
            engine = EngineKind.PARAKEET,
            live = false,
            languages = "English",
            wer = 4.0,
            license = "NVIDIA Open Model License",
            blurb = "The most accurate English model here. Text updates every moment and settles when you pause. Re-reads the phrase as you talk, so it needs a fast phone.",
            runsOn = "Flagship CPU",
            base = "$HF/mrfakename/parakeet-unified-ONNX/resolve/a34b30ffd9dc312a4012ec0305d28f92a1c86c66",
            files = listOf(
                ModelFile("preprocessor.onnx", 136430),
                ModelFile("encoder-model.q4.onnx", 435936308),
                ModelFile("decoder_joint-model.int8.onnx", 8995073),
                ModelFile("vocab.txt", 8952),
            ),
            source = "$HF/nvidia/parakeet-unified-en-0.6b",
        ),
        SpeechModel(
            id = "moonshine-base",
            name = "Moonshine Base (Lite)",
            vendor = "Useful Sensors",
            params = "61M",
            engine = EngineKind.MOONSHINE,
            live = false,
            languages = "English",
            wer = 12.3,
            license = "MIT",
            blurb = "Small and quick to download. Runs comfortably on most phones; text settles when you pause.",
            runsOn = "Any phone",
            base = "$HF/onnx-community/moonshine-base-ONNX/resolve/b1e9b6aae3c3c7298f10c3798393fdf38e8fbbad",
            files = listOf(
                ModelFile("config.json", 922),
                ModelFile("tokenizer.json", 3761754),
                ModelFile("onnx/encoder_model_quantized.onnx", 20513063),
                ModelFile("onnx/decoder_model_merged_quantized.onnx", 42498870),
            ),
            source = "$HF/UsefulSensors/moonshine-base",
        ),
    )

    // ---------- Read aloud (text to speech) ----------
    // StyleTTS 2 voices run with the app's ONNX Runtime. The prototype turns text into phonemes with eSpeak NG;
    // here words are looked up in Kokoro's own pronunciation dictionaries (misaki, Apache-2.0), pinned to a commit.

    const val SYSTEM_TTS_ID = "system-tts"
    private const val MISAKI = "https://raw.githubusercontent.com/hexgrad/misaki/e820629b96334db28227df37f280e4836d46fadb/misaki/data"
    private val US_LEXICON = listOf(
        ModelFile("lexicon/us_gold.json", 3000469, "$MISAKI/us_gold.json"),
        ModelFile("lexicon/us_silver.json", 3099517, "$MISAKI/us_silver.json"),
    )
    private val GB_LEXICON = listOf(
        ModelFile("lexicon/gb_gold.json", 2838552, "$MISAKI/gb_gold.json"),
        ModelFile("lexicon/gb_silver.json", 3663898, "$MISAKI/gb_silver.json"),
    )

    // A curated set of Kokoro's English voices (the first is the default).
    private val KOKORO_VOICES = listOf(
        Voice("af_heart", "Heart", "Female"),
        Voice("af_bella", "Bella", "Female"),
        Voice("af_nicole", "Nicole", "Female"),
        Voice("am_michael", "Michael", "Male"),
        Voice("am_fenrir", "Fenrir", "Male"),
        Voice("bf_emma", "Emma", "Female", accent = "UK"),
        Voice("bm_george", "George", "Male", accent = "UK"),
        Voice("bm_fable", "Fable", "Male", accent = "UK"),
    )
    private const val KOKORO_VOICE_BYTES = 522240L // 510 style rows × 256 floats

    val VOICE_MODELS = listOf(
        SpeechModel(
            id = SYSTEM_TTS_ID,
            name = "Android built-in voice",
            vendor = "Your phone",
            params = "no download",
            engine = EngineKind.SYSTEM_TTS,
            live = true,
            languages = "Many (phone settings)",
            wer = null,
            license = "Provided by Android",
            blurb = "Works straight away with no download, in every language your phone's text-to-speech supports (Android settings → Text-to-speech output).",
            runsOn = "Phone's voice",
            kind = ModelKind.VOICE,
        ),
        SpeechModel(
            id = "kitten-nano",
            name = "Kitten TTS Nano",
            vendor = "KittenML",
            params = "15M",
            engine = EngineKind.KITTEN,
            live = true,
            languages = "English (US)",
            wer = null,
            license = "Apache-2.0",
            blurb = "Tiny and quick: fast enough to read without pauses on most phones. Less natural than Kokoro.",
            runsOn = "Any phone",
            base = "$HF/onnx-community/KittenTTS-Nano-v0.8-ONNX/resolve/6078af30aab083436023d04ba845aedd9d1a9206",
            files = listOf(
                ModelFile("onnx/model.onnx", 56767095),
                ModelFile("voices.npz", 3278902),
            ) + US_LEXICON,
            voices = listOf(
                Voice("expr-voice-2-f", "Bella", "Female", prior = 0.8f),
                Voice("expr-voice-2-m", "Jasper", "Male", prior = 0.8f),
                Voice("expr-voice-3-f", "Luna", "Female", prior = 0.8f),
                Voice("expr-voice-3-m", "Bruno", "Male", prior = 0.8f),
                Voice("expr-voice-4-f", "Rosie", "Female", prior = 0.8f),
                Voice("expr-voice-4-m", "Hugo", "Male", prior = 0.9f),
                Voice("expr-voice-5-f", "Kiki", "Female", prior = 0.8f),
                Voice("expr-voice-5-m", "Leo", "Male", prior = 0.8f),
            ),
            source = "$HF/KittenML/kitten-tts-nano-0.8-fp32",
            kind = ModelKind.VOICE,
            recommended = true,
        ),
        SpeechModel(
            id = "kokoro-q8",
            name = "Kokoro",
            vendor = "hexgrad",
            params = "82M",
            engine = EngineKind.KOKORO,
            live = true,
            languages = "English (US, UK)",
            wer = null,
            license = "Apache-2.0",
            blurb = "The most natural, expressive voices here. Slower than Kitten, so it may pause between sentences on mid-range phones.",
            runsOn = "Recent phone",
            base = "$HF/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/1939ad2a8e416c0acfeecc08a694d14ef25f2231",
            files = listOf(
                ModelFile("onnx/model_quantized.onnx", 92361116),
                ModelFile("tokenizer.json", 3497),
            ) + KOKORO_VOICES.map { ModelFile("voices/${it.id}.bin", KOKORO_VOICE_BYTES) } + US_LEXICON + GB_LEXICON,
            voices = KOKORO_VOICES,
            source = "$HF/hexgrad/Kokoro-82M",
            kind = ModelKind.VOICE,
        ),
    )

    /** Every model: speech to text, then read aloud. */
    val ALL: List<SpeechModel> = MODELS + VOICE_MODELS

    fun byId(id: String?): SpeechModel? = ALL.firstOrNull { it.id == id }

    /** A speech-to-text model (never a voice). */
    fun speechById(id: String?): SpeechModel? = MODELS.firstOrNull { it.id == id }

    /** A read-aloud model (never a speech-to-text one). */
    fun voiceModelById(id: String?): SpeechModel? = VOICE_MODELS.firstOrNull { it.id == id }

    /** The on-device model this phone should start with: the big streaming model only with plenty of RAM. */
    fun recommendedFor(totalRamBytes: Long): String =
        if (totalRamBytes >= 7L * 1024 * 1024 * 1024) "nemotron-en" else "moonshine-base"

    fun languageLabel(value: String) = LANGUAGES.firstOrNull { it.value == value }?.label ?: value
}

fun fmtBytes(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.2f GB", n / 1e9)
    n >= 1_000_000 -> "${Math.round(n / 1e6)} MB"
    n >= 1_000 -> "${Math.round(n / 1e3)} KB"
    else -> "$n B"
}
