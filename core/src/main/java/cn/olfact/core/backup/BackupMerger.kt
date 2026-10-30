package cn.olfact.core.backup

/**
 * 合并策略：**只增不减**。备份导入永远不清空用户现有数据，
 * 冲突时按更新时间取新的；没有时间戳的设置项默认保留现有值。
 */
object BackupMerger {

    fun <T> mergeById(
        current: List<T>,
        incoming: List<T>,
        id: (T) -> String,
        updatedAt: (T) -> Long,
        section: String,
        preferIncoming: Boolean
    ): Pair<List<T>, MergeReport> {
        if (incoming.isEmpty()) return current to MergeReport(section)
        val result = ArrayList<T>(current.size + incoming.size)
        val index = HashMap<String, Int>(current.size * 2)
        current.forEach { item ->
            index[id(item)] = result.size
            result.add(item)
        }
        var added = 0
        var updated = 0
        var skipped = 0
        for (item in incoming) {
            val key = id(item)
            val at = index[key]
            if (at == null) {
                index[key] = result.size
                result.add(item)
                added++
            } else {
                val existing = result[at]
                if (preferIncoming && updatedAt(item) > updatedAt(existing)) {
                    result[at] = item
                    updated++
                } else {
                    skipped++
                }
            }
        }
        return result to MergeReport(section, added, updated, skipped)
    }

    fun mergeSettings(
        current: Map<String, String>,
        incoming: Map<String, String>,
        overwrite: Boolean
    ): Pair<Map<String, String>, MergeReport> {
        if (incoming.isEmpty()) return current to MergeReport("settings")
        val result = LinkedHashMap(current)
        var added = 0
        var updated = 0
        var skipped = 0
        for ((key, value) in incoming) {
            val existing = result[key]
            when {
                existing == null -> {
                    result[key] = value
                    added++
                }
                overwrite && existing != value -> {
                    result[key] = value
                    updated++
                }
                else -> skipped++
            }
        }
        return result to MergeReport("settings", added, updated, skipped)
    }

    fun mergeWhitelist(current: Collection<String>, incoming: List<String>): Pair<List<String>, MergeReport> {
        if (incoming.isEmpty()) return current.toList() to MergeReport("whitelist")
        val result = LinkedHashSet(current)
        var added = 0
        var skipped = 0
        for (domain in incoming) {
            if (result.add(domain)) added++ else skipped++
        }
        return result.toList() to MergeReport("whitelist", added, 0, skipped)
    }
}
