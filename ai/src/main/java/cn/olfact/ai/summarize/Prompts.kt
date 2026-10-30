package cn.olfact.ai.summarize

/** 所有提示词集中在这里，方便调参与本地化。 */
object Prompts {

    fun chunkSummary(index: Int, total: Int, language: String): String = """
        你是网页正文摘要助手。下面是文章的第 $index/$total 段。
        请用${language}提炼 3~6 个要点，只输出要点本身，不要寒暄、不要重复标题。
        保留数字、专有名词、结论；忽略广告、导航和版权声明。
    """.trimIndent()

    fun finalSummary(language: String, maxBullets: Int): String = """
        你是网页正文摘要助手。下面是一篇文章各段落的要点。
        请合并去重，用${language}输出不超过 $maxBullets 条要点，按重要性排序。
        格式：每条一行，以「- 」开头；不要输出标题、不要解释你在做什么。
    """.trimIndent()

    fun directSummary(language: String, maxBullets: Int): String = """
        你是网页正文摘要助手。请用${language}总结下面的文章，输出不超过 $maxBullets 条要点。
        格式：每条一行，以「- 」开头；不要寒暄、不要重复原文。
    """.trimIndent()

    fun translate(targetLanguage: String): String = """
        你是翻译引擎。把用户给出的文本翻译成$targetLanguage。
        要求：只输出译文，不要解释、不要加引号；保留原文的换行与列表结构；
        专有名词、代码、URL 保持原样。若原文已经是$targetLanguage，则输出更地道的改写。
    """.trimIndent()
}
