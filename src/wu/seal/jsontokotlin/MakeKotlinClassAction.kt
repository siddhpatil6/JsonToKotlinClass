package wu.seal.jsontokotlin

import com.google.gson.Gson
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import wu.seal.jsontokotlin.feedback.*
import wu.seal.jsontokotlin.ui.JsonInputDialog
import wu.seal.jsontokotlin.utils.ClassCodeFilter
import wu.seal.jsontokotlin.utils.LogUtil
import wu.seal.jsontokotlin.utils.executeCouldRollBackAction

import java.util.IllegalFormatFlagsException

/**
 * Plugin action
 * Created by Seal.Wu on 2017/8/18.
 */
class MakeKotlinClassAction : AnAction("MakeKotlinClass") {

    private val gson = Gson()

    override fun actionPerformed(event: AnActionEvent) {
        var jsonString: String = ""
        try {
            actionStart()
            val project = event.getData(PlatformDataKeys.PROJECT)
            val caret = event.getData(PlatformDataKeys.CARET)
            val editor = event.getData(PlatformDataKeys.EDITOR_EVEN_IF_INACTIVE)
            if (couldNotInsertCode(editor)) return
            val document = editor!!.document
            val editorText = document.text
            /**
             *  temp class name for insert
             */
            var tempClassName = ""
            val couldGetAndReuseClassNameInCurrentEditFileForInsertCode = couldGetAndReuseClassNameInCurrentEditFileForInsertCode(editorText)

            if (couldGetAndReuseClassNameInCurrentEditFileForInsertCode) {
                /**
                 * auto obtain the current class name
                 */
                tempClassName = getCurrentEditFileTemClassName(editorText)
            }
            val inputDialog = JsonInputDialog(tempClassName, project!!)
            inputDialog.show()
            val className = inputDialog.getClassName()
            val json = inputDialog.inputString
            if (json == null || json.isEmpty()) {
                return
            }
            jsonString = json

            if (reuseClassName(couldGetAndReuseClassNameInCurrentEditFileForInsertCode, className, tempClassName)) {
                executeCouldRollBackAction(project) {
                    /**
                     * if you don't clean then we will trick a conflict with two same class name error
                     */
                    cleanCurrentEditFile(document)
                }
            }
            if (insertKotlinCode(project, document, className, jsonString, caret)) {
                actionComplete()
            }


        } catch (e: Throwable) {
            dealWithException(jsonString, e)
            throw e
        }
    }

    private fun reuseClassName(couldGetAndReuseClassNameInCurrentEditFileForInserCode: Boolean, className: String, tempClassName: String) = couldGetAndReuseClassNameInCurrentEditFileForInserCode && className == tempClassName

    private fun couldNotInsertCode(editor: Editor?): Boolean {
        if (editor == null) {
            Messages.showWarningDialog("Please open a file in editor state for insert Kotlin code!", "No Editor File")
            return true
        }
        return false
    }

    private fun actionComplete() {
        Thread {
            sendActionInfo(gson.toJson(SuccessCompleteAction()))
        }.start()
    }

    private fun actionStart() {
        Thread {
            sendActionInfo(gson.toJson(StartAction()))
        }.start()
    }

    private fun insertKotlinCode(project: Project?, document: Document, className: String, jsonString: String, caret: Caret?): Boolean {
        ImportClassWriter.insertImportClassCode(project, document)

        val codeMaker: KotlinCodeMaker
        try {
            codeMaker = KotlinCodeMaker(className, jsonString)
        } catch (e: IllegalFormatFlagsException) {
            e.printStackTrace()
            Messages.showErrorDialog(e.message, "UnSupport Json")
            return false
        }

        executeCouldRollBackAction(project) {
            var offset: Int

            if (caret != null) {

                offset = caret.offset
                if (offset == 0) {
                    offset = document.textLength - 1
                }
            } else {
                offset = document.textLength - 1
            }
            document.insertString(Math.max(offset, 0), ClassCodeFilter.removeDuplicateClassCode(codeMaker.makeKotlinData()))
        }
        return true
    }

    private fun cleanCurrentEditFile(document: Document, editorText: String = document.text) {
        val cleanText = getCleanText(editorText)
        document.setText(cleanText)
    }

    internal fun getCleanText(editorText: String): String {
        val tempCleanText = editorText.substringBeforeLast("class")
        val cleanText = if (tempCleanText.trim().endsWith("data")) tempCleanText.trim().removeSuffix("data") else tempCleanText
        return cleanText
    }

    internal fun getCurrentEditFileTemClassName(editorText: String) = editorText.substringAfterLast("class")
            .substringBefore("(").substringBefore("{").trim()

    /**
     * whether we could reuse current class name declared in the edit file for inserting data class code
     * if we could use it,then we would clean the kotlin file as it was new file without any class code .
     */
    internal fun couldGetAndReuseClassNameInCurrentEditFileForInsertCode(editorText: String): Boolean {
        try {
            var couldGetAndReuseClassNameInCurrentEditFileForInsertCode = false
            val removeDocComment = editorText.replace(Regex("/\\*\\*(.|\n)*\\*/",RegexOption.MULTILINE), "")
            val removeDocCommentAndPackageDeclareText = removeDocComment
                    .replace(Regex("^(?:\\s*package |\\s*import ).*$", RegexOption.MULTILINE), "")
            if ((removeDocCommentAndPackageDeclareText.indexOf("class") == removeDocCommentAndPackageDeclareText.lastIndexOf("class")
                    && removeDocCommentAndPackageDeclareText.indexOf("class") != -1
                    && removeDocCommentAndPackageDeclareText.substringAfter("class").contains("(").not()
                    && removeDocCommentAndPackageDeclareText.substringAfter("class").contains(":").not()
                    && removeDocCommentAndPackageDeclareText.substringAfter("class").contains("=").not())
                    || (removeDocCommentAndPackageDeclareText.indexOf("class") == removeDocCommentAndPackageDeclareText.lastIndexOf("class")
                    && removeDocCommentAndPackageDeclareText.indexOf("class") != -1
                    && removeDocCommentAndPackageDeclareText.substringAfter("class").substringAfter("(")
                    .replace(Regex("\\s"), "").let { it.equals(")") || it.equals("){}") })) {
                couldGetAndReuseClassNameInCurrentEditFileForInsertCode = true
            }
            return couldGetAndReuseClassNameInCurrentEditFileForInsertCode
        } catch (e:Throwable) {
            LogUtil.e(e.message.toString(), e)
            return false
        }
    }
}
