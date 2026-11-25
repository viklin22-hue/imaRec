package site.webbing.audiorec

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识库文件夹选择项，用于设置页从服务端拉取后展示。
 *
 * 重构后 APP 操作范围限定在同一个知识库内，原"切换不同知识库"改为
 * "在同一知识库内切换不同文件夹"，此类型代表知识库下的一个文件夹。
 */
data class FolderOption(
    val id: String,
    val name: String,
)

/**
 * IMA 录音上传模式。
 */
enum class ImaUploadMode(
    val key: String,
    val label: String,
    val description: String,
) {
    AUDIO_ONLY(
        key = "audio_only",
        label = "纯音频模式",
        description = "仅上传 .m4a 原始录音文件到 IMA（默认行为）。",
    ),
    TRANSCRIPT_ONLY(
        key = "transcript_only",
        label = "纯文稿模式",
        description = "录音结束后先转录为文稿，仅以 Markdown 笔记上传到 IMA，便于其他 Agent 直接读取全文。",
    ),
    AUDIO_AND_TRANSCRIPT(
        key = "audio_and_transcript",
        label = "音频和文稿同时上传",
        description = "同时上传原始 .m4a 音频和转录后的 Markdown 文稿笔记到同一 IMA 文件夹。",
    );

    val needsTranscript: Boolean
        get() = this != AUDIO_ONLY

    val needsAudio: Boolean
        get() = this != TRANSCRIPT_ONLY

    companion object {
        fun fromKey(key: String?): ImaUploadMode =
            entries.firstOrNull { it.key == key } ?: AUDIO_ONLY
    }
}

/**
 * 语音转写引擎。
 */
enum class AsrEngine(
    val key: String,
    val label: String,
    val description: String,
) {
    SILICONFLOW_ALI(
        key = "siliconflow_ali",
        label = "阿里模型（OpenAI 兼容）",
        description = "使用硅基流动等 OpenAI 兼容接口（默认 FunAudioLLM/SenseVoiceSmall），整文件 HTTP 上传转写。",
    ),
    DOUBAO_SAUC_V2(
        key = "doubao_sauc_v2",
        label = "豆包流式语音识别 2.0",
        description = "本地 MediaCodec 流式解码 PCM 直连火山引擎 WSS（不走对象存储），支持方言、说话人分离与语义顺滑。",
    );

    companion object {
        fun fromKey(key: String?): AsrEngine =
            entries.firstOrNull { it.key == key } ?: SILICONFLOW_ALI
    }
}

/**
 * IMA 知识库上传配置。
 *
 * 对应 https://ima.qq.com/agent-interface 申请的 OpenAPI 凭证：
 * - [clientId] / [apiKey]：用于请求头 ima-openapi-clientid / ima-openapi-apikey
 * - [knowledgeBaseId]：目标知识库 ID（重构后固定为一个，不再支持多 KB 切换）
 * - [knowledgeBaseName]：知识库名称，仅用于界面展示，不参与接口调用
 * - [enabled]：是否在录音结束后自动上传到 IMA
 * - [allFolders]：最近一次从服务端拉取到的知识库下全部文件夹列表，持久化以便离线展示
 * - [activeFolders]：主页已展开为 Tab 的文件夹列表（用户可添加/移除）
 * - [currentFolderId]：主页当前选中 Tab 的文件夹 ID，双向绑定，上传时作为 folder_id
 * - [inspirationFolderId] / [inspirationFolderName]：灵感记录功能的目标文件夹，
 *   双击锁屏分段按钮进入灵感模式后，灵感期间的录音会保存并上传到此文件夹
 * - [uploadMode]：上传模式（纯音频 / 纯文稿 / 音频和文稿同时上传）
 * - [asrEngine]：语音转写引擎（阿里 OpenAI 兼容 / 豆包流式语音识别 2.0）
 * - [asrEndpoint] / [asrApiKey] / [asrModel]：OpenAI 兼容语音转写接口配置（默认硅基流动免费 SenseVoiceSmall）
 * - [doubaoWsUrl] / [doubaoResourceId] / [doubaoAppId] / [doubaoAccessToken]：豆包流式语音识别 2.0 配置
 */
data class ImaConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val apiKey: String = "",
    val knowledgeBaseId: String = "",
    val knowledgeBaseName: String = "",
    val allFolders: List<FolderOption> = emptyList(),
    val activeFolders: List<FolderOption> = emptyList(),
    val currentFolderId: String = "",
    val inspirationFolderId: String = "",
    val inspirationFolderName: String = "",
    val uploadMode: ImaUploadMode = ImaUploadMode.AUDIO_ONLY,
    val asrEngine: AsrEngine = AsrEngine.SILICONFLOW_ALI,
    val asrEndpoint: String = DEFAULT_ASR_ENDPOINT,
    val asrApiKey: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
    val doubaoWsUrl: String = DEFAULT_DOUBAO_WS_URL,
    val doubaoResourceId: String = DEFAULT_DOUBAO_RESOURCE_ID,
    val doubaoAppId: String = "",
    val doubaoAccessToken: String = "",
) {
    /** 判断配置是否完整，可发起一次上传。 */
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            apiKey.isNotBlank() &&
            knowledgeBaseId.isNotBlank()

    /** 按当前所选引擎判断语音转写配置是否完整。 */
    val isAsrConfigured: Boolean
        get() = when (asrEngine) {
            AsrEngine.SILICONFLOW_ALI -> asrEndpoint.isNotBlank() &&
                asrApiKey.isNotBlank() &&
                asrModel.isNotBlank()
            AsrEngine.DOUBAO_SAUC_V2 -> doubaoWsUrl.isNotBlank() &&
                doubaoResourceId.isNotBlank() &&
                doubaoAppId.isNotBlank() &&
                doubaoAccessToken.isNotBlank()
        }

    companion object {
        const val DEFAULT_ASR_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val DEFAULT_DOUBAO_WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
        const val DEFAULT_DOUBAO_RESOURCE_ID = "volc.seedasr.sauc.duration"
    }
}

/**
 * 基于 SharedPreferences 的 IMA 配置存储，并向 UI 暴露 [StateFlow]。
 *
 * 使用单例，确保 Service / ViewModel / SettingsScreen 共享同一 [StateFlow]，
 * 在任意位置修改配置后，其他持有者能立即收到更新。
 */
class ImaSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ImaConfig> = _config.asStateFlow()

    fun update(transform: (ImaConfig) -> ImaConfig) {
        Log.d(TAG, "update: start")
        val next = transform(_config.value)
        Log.d(TAG, "update: transform done, activeFolders=${next.activeFolders.size} currentFolderId=${next.currentFolderId}")
        save(next)
        Log.d(TAG, "update: save done")
        _config.value = next
        Log.d(TAG, "update: stateFlow emitted")
    }

    /**
     * 用从服务端拉取的最新文件夹列表覆盖 [ImaConfig.allFolders]，
     * 并同步更新 [ImaConfig.activeFolders] 与 [ImaConfig.currentFolderId]：
     * - 已被用户添加为 Tab 的文件夹，按最新 Name 更新显示名
     * - 云端已删除的文件夹，从 activeFolders 中移除；若被移除的恰是当前选中文件夹，清空选中态
     * - 当前 [currentFolderId] 在最新列表中存在时，刷新对应显示名
     */
    fun applyAllFolders(list: List<FolderOption>) {
        update { cfg ->
            val byId = list.associateBy { it.id }
            val newActiveFolders = cfg.activeFolders.mapNotNull { folder ->
                byId[folder.id]?.let { folder.copy(name = it.name) }
            }
            val newCurrentId = cfg.currentFolderId.takeIf { byId.containsKey(it) } ?: ""
            cfg.copy(
                allFolders = list,
                activeFolders = newActiveFolders,
                currentFolderId = newCurrentId,
            )
        }
    }

    /**
     * 设置唯一的目标知识库（重构后 KB 固定为一个）。
     * 切换知识库时清空文件夹相关状态（allFolders/activeFolders/currentFolderId），
     * 因为新知识库下的文件夹列表需要重新拉取。
     */
    fun setKnowledgeBase(id: String, name: String) {
        update { cfg ->
            // 若切换的是同一个 KB，保持文件夹状态不变
            if (cfg.knowledgeBaseId == id) {
                cfg.copy(knowledgeBaseName = name)
            } else {
                cfg.copy(
                    knowledgeBaseId = id,
                    knowledgeBaseName = name,
                    allFolders = emptyList(),
                    activeFolders = emptyList(),
                    currentFolderId = "",
                )
            }
        }
    }

    /**
     * 添加一个文件夹为主页 Tab，并切换为当前选中。
     * 若该文件夹已在 activeFolders 中，则仅切换选中态。
     */
    fun addFolderAndSelect(folder: FolderOption) {
        Log.d(TAG, "addFolderAndSelect: folder=${folder.id} name=${folder.name}")
        update { cfg ->
            val newActiveFolders = if (cfg.activeFolders.any { it.id == folder.id }) {
                cfg.activeFolders
            } else {
                cfg.activeFolders + folder
            }
            cfg.copy(
                activeFolders = newActiveFolders,
                currentFolderId = folder.id,
            )
        }
    }

    /**
     * 仅添加一个文件夹为主页 Tab，不改变当前选中态（currentFolderId 保持不变）。
     * 若该文件夹已在 activeFolders 中，则什么都不做。
     * 用途：灵感录音保存后把灵感文件夹加入 Tab 供用户查看，但不影响默认上传文件夹。
     */
    fun addFolder(folder: FolderOption) {
        Log.d(TAG, "addFolder: folder=${folder.id} name=${folder.name}")
        update { cfg ->
            if (cfg.activeFolders.any { it.id == folder.id }) {
                cfg
            } else {
                cfg.copy(activeFolders = cfg.activeFolders + folder)
            }
        }
    }

    /**
     * 从主页移除指定 Tab（不删除服务端文件夹）。
     * 若被移除的是当前选中 Tab：
     * - 若仍有其他 Tab，切换到第一个
     * - 否则清空选中态（主页回到无 Tab 状态）
     */
    fun removeFolder(folderId: String) {
        update { cfg ->
            val newActiveFolders = cfg.activeFolders.filterNot { it.id == folderId }
            val newCurrent = if (cfg.currentFolderId == folderId) {
                newActiveFolders.firstOrNull()?.id ?: ""
            } else {
                cfg.currentFolderId
            }
            cfg.copy(
                activeFolders = newActiveFolders,
                currentFolderId = newCurrent,
            )
        }
    }

    /** 切换当前选中的 Tab（同步更新 currentFolderId）。 */
    fun selectFolder(folderId: String) {
        update { cfg ->
            val folder = cfg.activeFolders.firstOrNull { it.id == folderId }
                ?: return@update cfg
            cfg.copy(currentFolderId = folder.id)
        }
    }

    /**
     * 设置灵感记录功能的目标文件夹。独立于默认上传文件夹与主页 Tab 选中态，
     * 仅更新 [ImaConfig.inspirationFolderId] / [ImaConfig.inspirationFolderName]。
     */
    fun setInspirationFolder(id: String, name: String) {
        update { it.copy(inspirationFolderId = id, inspirationFolderName = name) }
    }

    private fun load(): ImaConfig = ImaConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        knowledgeBaseId = prefs.getString(KEY_KB_ID, "").orEmpty(),
        knowledgeBaseName = prefs.getString(KEY_KB_NAME, "").orEmpty(),
        allFolders = readFolderList(prefs.getString(KEY_ALL_FOLDERS, null)),
        activeFolders = readFolderList(prefs.getString(KEY_ACTIVE_FOLDERS, null)),
        currentFolderId = prefs.getString(KEY_CURRENT_FOLDER, "").orEmpty(),
        inspirationFolderId = prefs.getString(KEY_INSPIRATION_FOLDER_ID, "").orEmpty(),
        inspirationFolderName = prefs.getString(KEY_INSPIRATION_FOLDER_NAME, "").orEmpty(),
        uploadMode = ImaUploadMode.fromKey(prefs.getString(KEY_UPLOAD_MODE, null)),
        asrEngine = AsrEngine.fromKey(prefs.getString(KEY_ASR_ENGINE, null)),
        asrEndpoint = prefs.getString(KEY_ASR_ENDPOINT, null)?.takeIf { it.isNotBlank() }
            ?: ImaConfig.DEFAULT_ASR_ENDPOINT,
        asrApiKey = prefs.getString(KEY_ASR_API_KEY, "").orEmpty(),
        asrModel = prefs.getString(KEY_ASR_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: ImaConfig.DEFAULT_ASR_MODEL,
        doubaoWsUrl = prefs.getString(KEY_DOUBAO_WS_URL, null)?.takeIf { it.isNotBlank() }
            ?: ImaConfig.DEFAULT_DOUBAO_WS_URL,
        doubaoResourceId = prefs.getString(KEY_DOUBAO_RESOURCE_ID, null)?.takeIf { it.isNotBlank() }
            ?: ImaConfig.DEFAULT_DOUBAO_RESOURCE_ID,
        doubaoAppId = prefs.getString(KEY_DOUBAO_APP_ID, "").orEmpty(),
        doubaoAccessToken = prefs.getString(KEY_DOUBAO_ACCESS_TOKEN, "").orEmpty(),
    )

    private fun save(config: ImaConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_CLIENT_ID, config.clientId)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_KB_ID, config.knowledgeBaseId)
            putString(KEY_KB_NAME, config.knowledgeBaseName)
            putString(KEY_ALL_FOLDERS, writeFolderList(config.allFolders))
            putString(KEY_ACTIVE_FOLDERS, writeFolderList(config.activeFolders))
            putString(KEY_CURRENT_FOLDER, config.currentFolderId)
            putString(KEY_INSPIRATION_FOLDER_ID, config.inspirationFolderId)
            putString(KEY_INSPIRATION_FOLDER_NAME, config.inspirationFolderName)
            putString(KEY_UPLOAD_MODE, config.uploadMode.key)
            putString(KEY_ASR_ENGINE, config.asrEngine.key)
            putString(KEY_ASR_ENDPOINT, config.asrEndpoint)
            putString(KEY_ASR_API_KEY, config.asrApiKey)
            putString(KEY_ASR_MODEL, config.asrModel)
            putString(KEY_DOUBAO_WS_URL, config.doubaoWsUrl)
            putString(KEY_DOUBAO_RESOURCE_ID, config.doubaoResourceId)
            putString(KEY_DOUBAO_APP_ID, config.doubaoAppId)
            putString(KEY_DOUBAO_ACCESS_TOKEN, config.doubaoAccessToken)
            apply()
        }
    }

    private fun readFolderList(json: String?): List<FolderOption> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) null else FolderOption(id = id, name = obj.optString("name"))
            }
        }.getOrElse { emptyList() }
    }

    private fun writeFolderList(list: List<FolderOption>): String {
        val arr = JSONArray()
        list.forEach { folder ->
            arr.put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
            })
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "ImaSettings"

        @Volatile
        private var instance: ImaSettings? = null

        fun get(context: Context): ImaSettings =
            instance ?: synchronized(this) {
                instance ?: ImaSettings(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "ima_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_KB_ID = "knowledge_base_id"
        private const val KEY_KB_NAME = "knowledge_base_name"
        private const val KEY_ALL_FOLDERS = "all_folders"
        private const val KEY_ACTIVE_FOLDERS = "active_folders"
        private const val KEY_CURRENT_FOLDER = "current_folder_id"
        private const val KEY_INSPIRATION_FOLDER_ID = "inspiration_folder_id"
        private const val KEY_INSPIRATION_FOLDER_NAME = "inspiration_folder_name"
        private const val KEY_UPLOAD_MODE = "upload_mode"
        private const val KEY_ASR_ENGINE = "asr_engine"
        private const val KEY_ASR_ENDPOINT = "asr_endpoint"
        private const val KEY_ASR_API_KEY = "asr_api_key"
        private const val KEY_ASR_MODEL = "asr_model"
        private const val KEY_DOUBAO_WS_URL = "doubao_ws_url"
        private const val KEY_DOUBAO_RESOURCE_ID = "doubao_resource_id"
        private const val KEY_DOUBAO_APP_ID = "doubao_app_id"
        private const val KEY_DOUBAO_ACCESS_TOKEN = "doubao_access_token"
    }
}
