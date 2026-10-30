package cn.olfact.ai.translate

/**
 * 整页双语对照的 JS 生成器。B2（web）负责注入，B4 的脚本引擎不参与：
 * 1. [collectParagraphsScript] 拿到段落文本；
 * 2. 调 [Translator] 批量翻译；
 * 3. [applyTranslationsScript] 把译文插到每段后面；
 * 4. [clearScript] 一键还原。
 */
object BilingualInjector {

    private const val SELECTOR = "p,li,h1,h2,h3,blockquote,td"

    fun collectParagraphsScript(minLength: Int = 20): String = """
        (function () {
          var nodes = Array.prototype.slice.call(document.querySelectorAll('$SELECTOR'));
          var out = [];
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.closest && el.closest('.olfact-bilingual')) continue;
            var text = (el.innerText || '').trim();
            if (text.length < $minLength) continue;
            if (el.querySelector && el.querySelector('$SELECTOR')) continue;
            out.push(text);
            if (out.length >= 200) break;
          }
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** [translationsJson] 形如 `["译文1","译文2"]`，与段落数组一一对应。 */
    fun applyTranslationsScript(translationsJson: String): String = """
        (function () {
          var translations = $translationsJson;
          var nodes = Array.prototype.slice.call(document.querySelectorAll('$SELECTOR'));
          var index = 0;
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.closest && el.closest('.olfact-bilingual')) continue;
            var text = (el.innerText || '').trim();
            if (text.length < 20) continue;
            if (el.querySelector && el.querySelector('$SELECTOR')) continue;
            if (index >= translations.length) break;
            var id = 'olfact-tr-' + index;
            var old = document.getElementById(id);
            if (old) old.remove();
            var div = document.createElement('div');
            div.id = id;
            div.className = 'olfact-bilingual';
            div.style.cssText = 'margin:2px 0 10px;padding-left:8px;border-left:3px solid rgba(128,171,255,.7);' +
              'color:#5B7FBF;font-size:.94em;line-height:1.5;';
            div.textContent = translations[index];
            el.insertAdjacentElement('afterend', div);
            index++;
          }
          return index;
        })();
    """.trimIndent()

    fun selectionScript(): String = """
        (function () {
          var text = String(window.getSelection ? window.getSelection().toString() : '');
          return text.trim();
        })();
    """.trimIndent()

    fun clearScript(): String = """
        (function () {
          var nodes = document.querySelectorAll('.olfact-bilingual');
          for (var i = 0; i < nodes.length; i++) { nodes[i].parentNode.removeChild(nodes[i]); }
          return nodes.length;
        })();
    """.trimIndent()
}
