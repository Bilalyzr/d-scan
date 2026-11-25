package com.cleansweep.app.ui.clean

import android.content.res.Resources
import com.cleansweep.app.R
import com.cleansweep.app.domain.scanner.trash.Refusal
import com.cleansweep.app.domain.scanner.trash.TrashRefusal
import com.cleansweep.app.ui.common.formatBytes
import com.cleansweep.app.ui.common.formatCount

/**
 * The words for what came of moving files to Safe Trash. Shared by the Suggestions Hub and the screen of one
 * group of duplicates, so that both say the same thing about the same outcome.
 */

internal fun Resources.movedMessage(
    outcome: CleanOutcome,
): String = when (outcome.moved.size) {
    0 -> getString(R.string.hub_msg_nothing_moved)
    1 -> getString(R.string.hub_msg_moved_one, formatBytes(outcome.bytes))
    else -> getString(
        R.string.hub_msg_moved_many,
        formatCount(outcome.moved.size.toLong()),
        formatBytes(outcome.bytes),
    )
}

/** `2 files not moved: 1 changed since the scan, 1 on storage the app cannot write to`. */
internal fun Resources.refusedMessage(
    refusals: List<Refusal>,
): String {
    val reasons = refusals.countedByReason().joinToString { (reason, count) ->
        formatCount(count.toLong()) + " " + getString(reason.labelRes())
    }
    return getString(R.string.hub_msg_not_moved, formatCount(refusals.size.toLong()), reasons)
}

private fun TrashRefusal.labelRes(): Int = when (this) {
    TrashRefusal.NOT_INDEXED -> R.string.refusal_not_indexed
    TrashRefusal.NOT_SUGGESTED -> R.string.refusal_not_suggested
    TrashRefusal.WHITELISTED -> R.string.refusal_whitelisted
    TrashRefusal.PROTECTED_ORIGINAL -> R.string.refusal_protected_original
    TrashRefusal.CHANGED_SINCE_SUGGESTED -> R.string.refusal_changed
    TrashRefusal.GONE -> R.string.refusal_gone
    TrashRefusal.STORAGE_READ_ONLY -> R.string.refusal_read_only
    TrashRefusal.COULD_NOT_MOVE -> R.string.refusal_could_not_move
}

internal fun Resources.restoredMessage(
    count: Int,
): String = getQuantityString(R.plurals.hub_msg_restored, count, formatCount(count.toLong()))
