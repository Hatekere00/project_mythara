package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.analysis.AnalysisInstructionStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `save_analysis_instruction` — teach Mythara a new way to conduct
 * personality / relationship analysis.
 *
 * This is how the analysis pipeline self-evolves. The agent reads an
 * analysis technique (e.g. `web_fetch` an article on personality
 * inference, or the user describing how they want it done), distils it
 * into a concrete instruction, and saves it here. The instruction is
 * then automatically prepended to the relevant Gemma prompts in the
 * persona / contact-analysis / self-profile passes — no code change,
 * the analysis genuinely changes based on what Mythara has learned.
 */
@Singleton
class SaveAnalysisInstructionTool @Inject constructor(
    private val store: AnalysisInstructionStore,
) : Tool {

    override val name: String = "save_analysis_instruction"

    override val description: String =
        "Teach Mythara a new way to conduct personality/relationship analysis. Use this after web_fetch-ing an " +
            "analysis technique you want to adopt, or when the user describes how they want their analysis done. " +
            "The `instruction` is stored durably and is automatically injected into future analysis passes " +
            "(persona, per-contact relationship analysis, the user's own Big Five) — so the analysis evolves. " +
            "`applies_to` scopes it: 'persona' (the user's behaviour persona), 'contacts' (per-contact analysis), " +
            "'self' (the user's own Big Five), or 'all'. Phrase `instruction` as a clear, actionable guideline " +
            "(e.g. 'When estimating extraversion, weight initiation of conversations more than message volume')."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "instruction",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "A clear, actionable analysis guideline to adopt.")
                    },
                )
                put(
                    "applies_to",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { AnalysisInstructionStore.VALID_SCOPES.forEach { add(JsonPrimitive(it)) } })
                        put("description", "Which analysis passes this applies to: persona, contacts, self, or all.")
                    },
                )
                put(
                    "source_url",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Optional — the URL this technique was learned from.")
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("instruction")); add(JsonPrimitive("applies_to")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val instruction = (args["instruction"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (instruction.isEmpty()) return ToolResult(false, """{"error":"missing_instruction"}""")
        val appliesTo = (args["applies_to"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        if (appliesTo !in AnalysisInstructionStore.VALID_SCOPES) {
            return ToolResult(
                false,
                buildJsonObject {
                    put("error", "bad_applies_to")
                    put("got", appliesTo)
                    put("valid", buildJsonArray { AnalysisInstructionStore.VALID_SCOPES.forEach { add(JsonPrimitive(it)) } })
                }.toString(),
            )
        }
        val sourceUrl = (args["source_url"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }

        val saved = runCatching { store.save(instruction, appliesTo, sourceUrl) }.getOrElse {
            Log.w(TAG, "save failed: ${it.message}")
            false
        }
        if (!saved) {
            return ToolResult(false, """{"error":"store_failed","detail":"Couldn't persist the instruction."}""")
        }
        Log.d(TAG, "learned analysis instruction (applies=$appliesTo): \"${instruction.take(80)}\"")
        return ToolResult(
            true,
            buildJsonObject {
                put("ok", true)
                put("applies_to", appliesTo)
                put("stored", instruction)
                put(
                    "detail",
                    "Stored — this guideline will be applied to future $appliesTo analysis passes automatically.",
                )
            }.toString(),
        )
    }

    companion object {
        private const val TAG = "Mythara/AnalysisInstr"
    }
}
