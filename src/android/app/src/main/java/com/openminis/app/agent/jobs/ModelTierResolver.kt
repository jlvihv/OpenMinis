package com.openminis.app.agent.jobs

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

// [T-p1-delegate-task] Which model tier a helper runs on (design v4 §5).
// Default `primary`; the delegating model opts IN to `sub` only for simple,
// bounded, self-verifiable work. No `auto`: a rule keyed on "is a Sub group
// configured" would downgrade complex work purely because a cheaper group
// exists, which has nothing to do with task fitness.
/**
 * [T-sub-agents-v1] Which model an Auto sub agent runs on.
 *
 * Only consulted when the definition pins no Model Group — a pinned one
 * outranks the model's choice entirely, because the user set it deliberately.
 *
 * Port of iOS `SubAgentModelChoice` (Agent/Jobs/ModelTierResolver.swift).
 */
enum class SubAgentModelChoice(val wire: String) {
    /** Continue with the same model this conversation runs on. */
    SAME_AS_PARENT("same_as_me"),

    /** The user's default group — typically their strongest model. */
    DEFAULT_MODEL("default_model"),

    /** The user's light group — typically smaller, faster, cheaper. */
    SUB_MODEL("sub_model"),
    ;

    companion object {
        /**
         * Unknown / absent values fall back to the parent's model: the safe
         * direction, since it is what the conversation is already using and
         * what the user actually chose. A fan-out that guessed
         * `default_model` would silently spend far more than the model the
         * user picked.
         */
        fun parse(raw: String?): SubAgentModelChoice {
            val v = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == v } ?: SAME_AS_PARENT
        }
    }
}

enum class HelperModelTier(val wire: String) {
    PRIMARY("primary"), SUB("sub");

    companion object {
        fun parse(raw: String?): HelperModelTier = when (raw?.trim()?.lowercase()) {
            "sub" -> SUB
            else -> PRIMARY
        }
    }
}

/**
 * [T-sub-agents-v1] Where a sub agent's model came from. Reported to the parent
 * model as `model_origin`, so a fan-out that behaves inconsistently can be
 * explained without guessing.
 *
 * Port of iOS `HelperModelOrigin`; the wire values must match.
 */
enum class HelperModelOrigin(val wire: String) {
    /** The definition pinned a Model Group — the user's choice, outranks all. */
    PINNED("pinned"),

    /** The parent conversation's own model, passed through. */
    INHERITED("inherited"),

    /** The user's default group, because the model asked for `default_model`. */
    DEFAULT_GROUP("default_group"),

    /** The user's light group, because the model asked for `sub_model`. */
    SUB_GROUP("sub_group"),
}

/** The binding a helper session should be created with, plus what was actually used. */
data class HelperModelResolution(
    /** `sessions.model_binding` JSON, or null to leave the row pinned to [seedModelId]. */
    val bindingJson: String?,
    /** `sessions.model_id` seed. */
    val seedModelId: String,
    val modelLabel: String,
    val tierUsed: HelperModelTier,
    /** [T-sub-agents-v1] How this binding was chosen. */
    val origin: HelperModelOrigin = HelperModelOrigin.INHERITED,
    /**
     * [T-sub-agents-v1] True when the definition pinned a group that could not
     * be routed, so this fell back to inheriting.
     *
     * Surfaced in the result JSON as `model_group_unavailable`: silently
     * running on a different model than the user pinned is exactly the kind of
     * thing that reads as a bug months later.
     */
    val modelGroupUnavailable: Boolean = false,
    /**
     * [T-android-subagent-model-strategy] The Model Group's own name, when the
     * binding came from one. The card names the group the user configured
     * rather than a group id, which means nothing to them.
     */
    val modelGroupName: String? = null,
    /**
     * [T-android-agent-model-identity] The RESOLVED binding's identity, as
     * three separate facts iOS keeps separate too (HelperModelIdentity.swift):
     * which configured entry the resolver picked, on which provider instance,
     * and which model id that entry names.
     *
     * [modelLabel] alone is a display string; it cannot answer "which of my two
     * Anthropic instances ran this" or survive a rename. These are what the
     * detail sheet's Model group / Model tier rows read, and what a payload
     * reloaded after a restart needs in order to still say where the run went.
     *
     * All nullable: a binding inherited from the parent conversation names no
     * entry of its own, and an old payload carries none of them.
     */
    val resolvedEntryId: String? = null,
    val resolvedProviderLabel: String? = null,
    val resolvedProviderType: String? = null,
    val resolvedModelId: String? = null,
    val resolvedModelName: String? = null,
)

internal data class DirectAgentModelSelection(
    val entry: ModelEntry,
    val origin: HelperModelOrigin,
    val pinnedModelUnavailable: Boolean = false,
)

/** Selection policy shared by delegated and scheduled child sessions. */
internal fun selectDirectAgentModel(
    config: com.openminis.app.data.model.ProviderConfig,
    pinnedEntryId: String?,
    choice: SubAgentModelChoice,
    parentEntryId: String?,
    parentModelId: String?,
    available: (ModelEntry) -> Boolean,
): DirectAgentModelSelection? {
    fun entry(id: String?) = config.modelEntries.firstOrNull { it.id == id && available(it) }
    fun inherited(): DirectAgentModelSelection? {
        val parent = if (parentEntryId != null) entry(parentEntryId)
            else config.modelEntries.firstOrNull { it.model.id == parentModelId && available(it) }
        return parent?.let { DirectAgentModelSelection(it, HelperModelOrigin.INHERITED) }
    }
    if (pinnedEntryId != null) {
        return entry(pinnedEntryId)?.let { DirectAgentModelSelection(it, HelperModelOrigin.PINNED) }
            ?: inherited()?.copy(pinnedModelUnavailable = true)
    }
    val (id, origin) = when (choice) {
        SubAgentModelChoice.SAME_AS_PARENT -> return inherited()
        SubAgentModelChoice.DEFAULT_MODEL -> config.defaultModelEntryId to HelperModelOrigin.DEFAULT_GROUP
        SubAgentModelChoice.SUB_MODEL -> config.subModelEntryId to HelperModelOrigin.SUB_GROUP
    }
    return entry(id)?.let { DirectAgentModelSelection(it, origin) } ?: inherited()
}

object ModelTierResolver {
    private fun resolveChoice(
        repo: ProviderRepository, pinnedEntryId: String?, choice: SubAgentModelChoice,
        parentBindingJson: String?, parentModelId: String?, parentActiveEntryId: String?,
    ): HelperModelResolution? {
        val bindingId = runCatching {
            JSONObject(parentBindingJson ?: "{}").optString("entryId").takeIf { it.isNotBlank() }
        }.getOrNull()
        val selected = selectDirectAgentModel(
            repo.config.value, pinnedEntryId, choice, parentActiveEntryId ?: bindingId, parentModelId,
        ) { e ->
            val instance = repo.instance(e.providerInstanceId)
            !e.isHidden && !e.isUnavailableFromProvider && instance?.isEnabled == true && repo.hasAnyCredential(instance)
        } ?: return null
        val entry = selected.entry
        val binding = if (selected.origin == HelperModelOrigin.INHERITED && bindingId == entry.id) parentBindingJson
            else JSONObject().put("type", "entry").put("entryId", entry.id).toString()
        return HelperModelResolution(
            binding, entry.model.id, entry.model.displayName,
            if (selected.origin == HelperModelOrigin.SUB_GROUP) HelperModelTier.SUB else HelperModelTier.PRIMARY,
            origin = selected.origin,
            modelGroupUnavailable = selected.pinnedModelUnavailable,
            modelGroupName = entry.model.displayName,
            resolvedEntryId = entry.id,
            resolvedProviderLabel = repo.instance(entry.providerInstanceId)?.label,
            resolvedProviderType = repo.instance(entry.providerInstanceId)?.providerType?.name?.lowercase(),
            resolvedModelId = entry.model.id,
            resolvedModelName = entry.model.displayName,
        )
    }

    fun resolve(tier: HelperModelTier, repo: ProviderRepository, parentBindingJson: String?, parentModelId: String?, parentActiveEntryId: String?): HelperModelResolution? =
        resolveChoice(repo, null, if (tier == HelperModelTier.SUB) SubAgentModelChoice.SUB_MODEL else SubAgentModelChoice.SAME_AS_PARENT,
            parentBindingJson, parentModelId, parentActiveEntryId)

    fun resolveForSubAgent(pinnedEntryId: String?, choice: SubAgentModelChoice, repo: ProviderRepository, parentBindingJson: String?, parentModelId: String?, parentActiveEntryId: String?): HelperModelResolution? =
        resolveChoice(repo, pinnedEntryId, choice, parentBindingJson, parentModelId, parentActiveEntryId)
}
