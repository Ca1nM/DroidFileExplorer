package com.sza.androidfolderexplorer.data.network

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A request to open a saved connection, made from the hamburger menu and picked up by the Network screen. */
object NetworkRequests {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    fun connect(id: String) { _pending.value = id }
    fun consume() { _pending.value = null }
}

/** Turns a document-provider link into a readable account name such as "Drive · you@gmail.com". */
object CloudNames {

    /** True for names made from a raw document id, which older versions saved. */
    fun looksRaw(name: String) =
        name.isBlank() || "acc=" in name || "doc=" in name || name.startsWith("Storage (") || name == "Google Drive"

    fun describe(context: Context, tree: Uri): String {
        val authority = tree.authority ?: return "Cloud storage"
        val pm = context.packageManager
        val provider = runCatching { pm.resolveContentProvider(authority, 0)?.loadLabel(pm)?.toString() }.getOrNull()

        // The provider's "roots" carry the account title and, usually, the account email.
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull().orEmpty()
        val account = treeId.substringBefore(';')   // e.g. "acc=4" for Google Drive accounts
        var title: String? = null
        var summary: String? = null
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildRootsUri(authority),
                arrayOf(DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY, DocumentsContract.Root.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { c ->
                var best: Triple<String?, String?, Int>? = null
                while (c.moveToNext()) {
                    val rootDoc = c.getString(2).orEmpty()
                    // Exact folder match beats same-account match beats any root.
                    val score = when {
                        rootDoc == treeId -> 3
                        account.isNotEmpty() && rootDoc.substringBefore(';') == account -> 2
                        else -> 1
                    }
                    if (best == null || score > best!!.third) best = Triple(c.getString(0), c.getString(1), score)
                }
                // Only trust a root when it clearly belongs to this account (or it is the only one).
                if (best != null && (best!!.third >= 2 || c.count == 1)) { title = best!!.first; summary = best!!.second }
            }
        }
        val service = when {
            "google.android.apps.docs" in authority -> "Google Drive"
            !provider.isNullOrBlank() -> provider
            !title.isNullOrBlank() -> title
            else -> authority.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        // Providers only list accounts to system apps, so the email usually isn't available; the account
        // number at least tells several Google accounts apart. People can rename it.
        val accountNumber = Regex("acc=(\\d+)").find(treeId)?.groupValues?.get(1)
        return when {
            !summary.isNullOrBlank() -> "$service · $summary"
            accountNumber != null -> "$service · account $accountNumber"
            else -> service!!
        }
    }
}
