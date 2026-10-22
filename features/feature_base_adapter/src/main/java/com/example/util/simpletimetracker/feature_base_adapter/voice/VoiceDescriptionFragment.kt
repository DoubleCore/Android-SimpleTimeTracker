package com.example.util.simpletimetracker.feature_base_adapter.voice

import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.Toast
import java.util.Locale

/** A framework fragment keeps the recognition result across activity recreation. */
@Suppress("DEPRECATION")
class VoiceDescriptionFragment : Fragment() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state == null) {
            try {
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说出记录描述")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }, 1)
            } catch (_: ActivityNotFoundException) {
                message("设备没有可用的语音识别服务，请使用键盘输入")
                close()
            } catch (_: SecurityException) {
                message("语音服务无法启动，请检查系统语音权限")
                close()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (resultCode != Activity.RESULT_OK || words.isNullOrBlank()) {
            close()
            return
        }
        // Text is only applied after explicit confirmation; cancelling never modifies the draft.
        val host = activity ?: return
        val preview = EditText(host).apply { setText(words); minLines = 2 }
        AlertDialog.Builder(host)
            .setTitle("确认语音描述")
            .setView(preview)
            .setPositiveButton("追加到描述") { _, _ ->
                val target = host.findViewById<EditText>(arguments.getInt("field"))
                val original = arguments.getString("original", "")
                if (target == null || target.text.toString() != original) {
                    message("描述页面已变化，本次语音未写入，请重新输入")
                } else {
                    val result = preview.text.toString().trim()
                    if (result.isNotEmpty()) {
                        target.setText(if (original.isBlank()) result else "$original\n$result")
                        target.setSelection(target.text.length)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { close() }
            .show()
    }

    private fun message(text: String) {
        activity?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }
    private fun close() {
        fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
    }

    companion object {
        fun start(field: EditText) {
            var context: Context = field.context
            while (context is ContextWrapper && context !is Activity) context = context.baseContext
            val host = context as? Activity ?: return
            if (host.isFinishing || host.fragmentManager.findFragmentByTag("description_voice") != null) return
            // The chosen system recognizer may process audio online.
            AlertDialog.Builder(host).setTitle("语音输入")
                .setMessage("将调用系统语音服务，音频可能由该服务联网处理。识别后可修改并确认描述。")
                .setPositiveButton("开始") { _, _ ->
                    val fragment = VoiceDescriptionFragment().apply {
                        arguments = Bundle().apply {
                            putInt("field", field.id)
                            putString("original", field.text.toString())
                        }
                    }
                    host.fragmentManager.beginTransaction().add(fragment, "description_voice").commitAllowingStateLoss()
                }.setNegativeButton("取消", null).show()
        }
    }
}
