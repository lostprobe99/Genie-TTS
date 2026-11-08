package com.genie.tts.speech

import com.genie.tts.character.CharacterPackSchema
import org.json.JSONObject
import java.io.File

class GenieCase(val root: File) {
    val text: String
    val sampleRate: Int
    val tensors: Map<String, OwnedTensor>
    val modelDir: File = CharacterPackSchema.modelsDir(root)

    init {
        val manifest = JSONObject(CharacterPackSchema.manifestFile(root).readText())
        text = manifest.optString("text")
        sampleRate = manifest.getInt(CharacterPackSchema.KEY_SAMPLE_RATE)
        val tensorJson = manifest.getJSONObject(CharacterPackSchema.KEY_TENSORS)
        val loaded = HashMap<String, OwnedTensor>()
        for (name in tensorJson.keys()) {
            val spec = tensorJson.getJSONObject(name)
            val shapeJson = spec.getJSONArray("shape")
            val shape = LongArray(shapeJson.length()) { shapeJson.getLong(it) }
            loaded[name] = OwnedTensor.fromFile(File(root, spec.getString("file")), spec.getString("dtype"), shape)
        }
        tensors = loaded
    }

    fun model(name: String): File = File(modelDir, name)

    companion object {
        fun find(candidates: List<File>): GenieCase? {
            return candidates.firstOrNull { CharacterPackSchema.manifestFile(it).isFile }?.let { GenieCase(it) }
        }
    }
}

/** 一个角色已经读进内存的清单和参考张量。同一角色连续合成几句时复用这一份。 */
internal class LoadedCharacter(val id: String, root: File) {
    val case: GenieCase = GenieCase(root)
}
