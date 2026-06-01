package com.example.bilitranscript

import android.content.Context
import android.util.Log

/**
 * 人声分离（剥离背景音乐 BGM）。
 *
 * 设计目标：在识别前把 16kHz 单声道 PCM 里的伴奏去掉，只留人声，
 * 这样「有音乐也能识别清晰」——而且分离后连小模型(SenseVoice)都读得很准。
 *
 * ⚠️ 现状（诚实说明）：
 *   分离需要一个神经网络模型（UVR/MDX-Net 或 Demucs 的 ONNX）+ 推理后端。
 *   - 模型文件较大（约 50–100MB），需自行放入 assets/models/separation/，无法随代码附带。
 *   - 推理后端两条路（任选其一，详见 UPGRADE.md）：
 *       A) sherpa-onnx 的离线源分离 API（若你的 sherpa-onnx 版本提供）；
 *       B) 额外引入 com.microsoft.onnxruntime:onnxruntime-android 直接跑 MDX-Net
 *          —— 注意它与 sherpa 自带的 libonnxruntime.so 可能有 native 冲突，需用
 *          packagingOptions 处理，集成前务必单机验证。
 *
 *   在模型/后端就绪前，[isAvailable] 返回 false，[separate] 原样透传（不改变行为、不崩溃）。
 *   管线只在 设置开启 且 [isAvailable] 为真 时才会调用分离，所以现在是安全的「空档位」。
 */
class VocalSeparator(private val context: Context) {

    companion object {
        private const val TAG = "VocalSeparator"
        private const val SEPARATION_DIR = "models/separation"
    }

    /** 分离模型是否已就绪（assets 里存在 .onnx）。 */
    fun isModelPresent(): Boolean = try {
        context.assets.list(SEPARATION_DIR)?.any { it.endsWith(".onnx") } == true
    } catch (e: Exception) {
        false
    }

    /**
     * 当前是否真正可用 = 模型存在 且 推理后端已接入。
     * 接入后端后，把 BACKEND_WIRED 改成 true。
     */
    fun isAvailable(): Boolean = isModelPresent() && BACKEND_WIRED

    /**
     * 分离人声。后端未接入时原样返回（透传）。
     * @param samples 16kHz 单声道 float PCM
     */
    fun separate(samples: FloatArray, onProgress: ((Float) -> Unit)? = null): FloatArray {
        if (!isAvailable()) {
            Log.d(TAG, "人声分离未启用（模型或后端缺失），透传原音频")
            onProgress?.invoke(1f)
            return samples
        }
        // TODO(接入后端): 在此把 samples 送入 MDX-Net/Demucs 推理，返回人声轨。
        // 见 UPGRADE.md「人声分离」章节的参考实现。
        onProgress?.invoke(1f)
        return samples
    }

    // 接入推理后端后改为 true
    private val BACKEND_WIRED = false
}
