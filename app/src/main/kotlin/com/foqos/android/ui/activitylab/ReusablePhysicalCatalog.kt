package com.foqos.android.ui.activitylab

import android.content.Context
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import org.json.JSONArray
import org.json.JSONObject

/** Local, reusable NFC/QR library independent of a profile's lifetime. */
class ReusablePhysicalCatalog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "reusable_physical_credentials_v1", Context.MODE_PRIVATE)

    fun items(): List<PhysicalUnblockItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val type = runCatching { PhysicalUnblockType.valueOf(obj.getString("type")) }.getOrNull()
                ?: return@mapNotNull null
            val id = obj.optString("id")
            val code = obj.optString("code")
            if (id.isBlank() || code.isBlank()) return@mapNotNull null
            PhysicalUnblockItem(
                id = id, type = type, codeValue = code,
                name = obj.optString("name").ifBlank { type.name },
            )
        }
    }.getOrDefault(emptyList())

    fun remember(items: List<PhysicalUnblockItem>) {
        if (items.isEmpty()) return
        val merged = (items() + items).associateBy { it.id }.values
        val array = JSONArray()
        merged.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("type", item.type.name).put("code", item.codeValue))
        }
        check(prefs.edit().putString("items", array.toString()).commit()) {
            "再利用用のNFC・QRを保存できません"
        }
    }
}
