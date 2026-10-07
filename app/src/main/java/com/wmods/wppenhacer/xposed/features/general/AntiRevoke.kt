package com.wmods.wppenhacer.xposed.features.general

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.highcapable.yukihookapi.hook.param.HookParam
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.FStatusWpp
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp
import com.wmods.wppenhacer.xposed.core.db.DelMessageStore
import com.wmods.wppenhacer.xposed.core.db.MessageStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.YukiLog
import java.lang.reflect.Method
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class AntiRevoke(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private val allRevokedMessageIds = ConcurrentHashMap.newKeySet<String>()
        private var cachedDeletedPrefix: String? = null

        private val dateFormatThreadLocal = ThreadLocal.withInitial {
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                Utils.application.resources.configuration.locales[0]
            )
        }

        private val revokedTimestampCache = ConcurrentHashMap<String, Long>()

        private fun getDeletedPrefix(): String {
            return cachedDeletedPrefix ?: run {
                val p = try {
                    UnobfuscatorCache.getInstance().getString("messagedeleted").ifEmpty { "Message deleted" }
                } catch (_: Throwable) {
                    "Message deleted"
                }
                cachedDeletedPrefix = p
                p
            }
        }

        private fun findObjectFMessage(param: HookParam): FMessageWpp? {
            val safeArgs = param.args?.filterNotNull() ?: return null
            safeArgs.firstOrNull { FMessageWpp.TYPE.isInstance(it) }?.let { return FMessageWpp(it) }
            val arg0 = param.args?.getOrNull(0) ?: return null
            val statusItem = StatusItemWpp.from(arg0) ?: return null
            return statusItem.fMessage
        }

        private fun cleanDeletedPrefix(text: String, prefix: String): String {
            var clean = text.trim()
            val p = prefix.trim()
            while (true) {
                val matched = when {
                    p.isNotEmpty() && clean.startsWith(p, ignoreCase = true) -> p
                    clean.startsWith("Message deleted", ignoreCase = true) -> "Message deleted"
                    clean.startsWith("Deleted message", ignoreCase = true) -> "Deleted message"
                    else -> null
                } ?: break
                clean = clean.substring(matched.length).trim()
                while (clean.startsWith("|") || clean.startsWith("•") || clean.startsWith("-")) {
                    clean = clean.substring(1).trim()
                }
            }
            return clean
        }

        private fun persistRevokedMessage(fMessage: FMessageWpp, messageID: String) {
            allRevokedMessageIds.add(messageID)
            val stripJID = fMessage.key.remoteJid.phoneNumber ?: return
            DelMessageStore.getInstance(Utils.application).insertMessage(
                stripJID,
                messageID,
                System.currentTimeMillis()
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun doHook() {
        Utils.databaseExecutor.execute {
            try {
                allRevokedMessageIds.addAll(DelMessageStore.getInstance(Utils.application).getAllMessageIds())
            } catch (t: Throwable) {
                logDebug(t)
            }
        }

        val antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(classLoader)
        val unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader)
        val statusPlaybackClass = Unobfuscator.loadStatusPlaybackViewClass(classLoader)
        val antiRevokeFStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(classLoader)

        antiRevokeFStatusMethod.hook {
            before {
                val fStatusKey = FStatusWpp.FStatusKey(args[1])
                val fstatus = fStatusKey.fStatus ?: return@before
                val fMessage = fstatus.fMessage ?: return@before
                if (!fStatusKey.isFromMe && handleRevocationAttempt(
                        fMessage,
                        fStatusKey.messageID
                    ) != 0
                ) {
                    result = 0
                }
            }
        }

        antiRevokeMessageMethod!!.hook {
            before {
                val args = args ?: return@before
                val fMessageObj = ReflectionUtils.getArg(args, FMessageWpp.TYPE, 0)
                if (fMessageObj == null) {
                    logDebug("FMessageObj is null in revoke!")
                    return@before
                }
                val fMessage = FMessageWpp(fMessageObj)
                val messageKey = fMessage.key
                val deviceJid = fMessage.deviceJid
                val messageId =
                    ReflectionUtils.getObjectField(fMessage.getObject(), "A01") as String


                val shouldIntercept = if (messageKey.remoteJid.isGroup) {
                    deviceJid != null && handleRevocationAttempt(fMessage, messageId) != 0
                } else {
                    !messageKey.isFromMe && handleRevocationAttempt(fMessage, messageId) != 0
                }
                if (shouldIntercept) {
                    val method = method as Method
                    if (method.returnType != Boolean::class.javaPrimitiveType) {
                        val constructor = method.returnType.constructors[0]
                        val params = ReflectionUtils.initArray(constructor.parameterTypes)
                        val instance = constructor.newInstance(*params)
                        result = instance
                    } else {
                        result = true
                    }
                }
            }
        }

        ConversationItemListener.conversationListeners.add(object :
            ConversationItemListener.OnConversationItemListener() {
            override fun onItemBind(
                fMessage: FMessageWpp,
                view: ViewGroup,
                position: Int,
                convertView: View?
            ) {
                val dateTextView = view.findViewById<TextView>(Utils.getID("date", "id"))
                bindRevokedMessageUI(fMessage, dateTextView, "antirevoke", view)
            }
        })

        unknownStatusPlaybackMethod.hook {
            after {
                val obj = ReflectionUtils.getArg(args, method.declaringClass, 0)
                val fMessage = findObjectFMessage(this)
                val field =
                    ReflectionUtils.getFieldByType(method.declaringClass, statusPlaybackClass)

                if (obj == null || field == null || fMessage == null) {
                    logDebug("Invalid parameters")
                    return@after
                }

                val objView = field.get(obj) ?: return@after
                val textViews =
                    ReflectionUtils.getFieldsByType(statusPlaybackClass, TextView::class.java)

                if (textViews.isEmpty()) {
                    logDebug("No text views found")
                    return@after
                }

                val dateId = Utils.getID("date", "id")
                for (textViewField in textViews) {
                    val textView = textViewField.get(objView) as? TextView
                    if (textView != null && textView.id == dateId) {
                        bindRevokedMessageUI(fMessage, textView, "antirevokestatus")
                        break
                    }
                }
            }
        }
    }

    private fun bindRevokedMessageUI(
        fMessage: FMessageWpp,
        dateTextView: TextView?,
        antirevokeType: String,
        boundView: View? = null
    ) {
        if (dateTextView == null) return
        val antirevokeValue = xprefs.getString(antirevokeType, "0")?.toIntOrNull() ?: 0
        if (antirevokeValue == 0) return

        val boundMessageId = fMessage.key.messageID
        val origId = fMessage.originalKey?.messageID
        val isRevoked = allRevokedMessageIds.contains(boundMessageId) ||
            (!origId.isNullOrEmpty() && allRevokedMessageIds.contains(origId))
        val revokedKey = if (isRevoked) {
            if (allRevokedMessageIds.contains(boundMessageId)) boundMessageId else origId
        } else null

        val wasRevoked = ReflectionUtils.getAdditionalInstanceField(dateTextView, "wasRevoked") == true

        if (!isRevoked || revokedKey == null) {
            if (wasRevoked) {
                ReflectionUtils.setAdditionalInstanceField(dateTextView, "wasRevoked", false)
                ReflectionUtils.setAdditionalInstanceField(dateTextView, "originalMessage", null)
                dateTextView.paint.isUnderlineText = false
                dateTextView.setOnClickListener(null)
                dateTextView.setCompoundDrawables(null, null, null, null)
                val prefix = getDeletedPrefix()
                val currentText = (dateTextView.text?.toString() ?: "").trim()
                val cleanTime = cleanDeletedPrefix(currentText, prefix)
                if (currentText != cleanTime) {
                    dateTextView.text = cleanTime
                }
            }
            return
        }

        ReflectionUtils.setAdditionalInstanceField(dateTextView, "wasRevoked", true)
        val prefix = getDeletedPrefix()
        val currentText = (dateTextView.text?.toString() ?: "").trim()
        val cleanTime = cleanDeletedPrefix(currentText, prefix)

        applyRevokedUI(dateTextView, antirevokeValue, revokedKey, boundView, boundMessageId, prefix, cleanTime)
    }

    private fun applyRevokedUI(
        dateTextView: TextView,
        antirevokeValue: Int,
        messageId: String,
        boundView: View?,
        boundMessageId: String,
        prefix: String,
        cleanTime: String
    ) {
        val cachedTimestamp = revokedTimestampCache[messageId]
        if (cachedTimestamp == null) {
            Utils.databaseExecutor.execute {
                val loadedTimestamp = DelMessageStore.getInstance(Utils.application)
                    .getTimestampByMessageId(messageId)
                if (loadedTimestamp > 0) {
                    revokedTimestampCache[messageId] = loadedTimestamp
                    mainHandler.post {
                        if (boundView == null || ConversationItemListener.isViewBoundToMessage(boundView, boundMessageId)) {
                            setupTimestampClick(dateTextView, loadedTimestamp, boundView, boundMessageId)
                        }
                    }
                }
            }
        } else if (cachedTimestamp > 0) {
            setupTimestampClick(dateTextView, cachedTimestamp, boundView, boundMessageId)
        }

        when (antirevokeValue) {
            1 -> {
                val formatted = if (cleanTime.isNotEmpty()) "$prefix | $cleanTime" else prefix
                dateTextView.text = formatted
                ReflectionUtils.setAdditionalInstanceField(
                    dateTextView,
                    "originalMessage",
                    cleanTime
                )
            }

            2 -> {
                val drawable = Utils.application.getDrawable(R.drawable.deleted)
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    null,
                    drawable,
                    null
                )
                dateTextView.compoundDrawablePadding = 5
            }
        }
    }

    private fun setupTimestampClick(
        dateTextView: TextView,
        timestamp: Long,
        boundView: View?,
        boundMessageId: String
    ) {
        val date = dateFormatThreadLocal.get()?.format(Date(timestamp)) ?: return
        dateTextView.paint.isUnderlineText = true
        dateTextView.setOnClickListener {
            if (boundView != null && !ConversationItemListener.isViewBoundToMessage(
                    boundView,
                    boundMessageId
                )
            ) return@setOnClickListener
            val toastMessage =
                Utils.application.getString(R.string.message_removed_on)
                    .format(date)
            Utils.showToast(toastMessage, Toast.LENGTH_LONG)
        }
    }

    private fun handleRevocationAttempt(fMessage: FMessageWpp, messageId: String): Int {
        try {
            handleRevocationAlert(fMessage)
        } catch (e: Exception) {
            log(e)
        }

        val revokeBoolean = xprefs.getString(
            if (fMessage.key.remoteJid.isStatus) "antirevokestatus" else "antirevoke",
            "0"
        )?.toIntOrNull() ?: 0

        if (revokeBoolean == 0) return 0

        if (!allRevokedMessageIds.contains(messageId)) {
            allRevokedMessageIds.add(messageId)
            Utils.databaseExecutor.execute {
                try {
                    persistRevokedMessage(fMessage, messageId)
                    val mConversation = WppCore.getCurrentConversation()
                    if (mConversation != null && fMessage.key.remoteJid.phoneNumber == WppCore.getCurrentUserJid()?.phoneNumber) {
                        mConversation.runOnUiThread {
                            ConversationItemListener.notifyDataSetChanged()
                        }
                    }
                } catch (e: Exception) {
                    logDebug(e)
                }
            }
        }
        return revokeBoolean
    }

    private fun formatRevocationMessage(fMessage: FMessageWpp): String? {
        var jidAuthor = fMessage.key.remoteJid
        var messageSuffix = Utils.application.getString(R.string.deleted_message)

        if (jidAuthor.isStatus) {
            messageSuffix = Utils.application.getString(R.string.deleted_status)
            jidAuthor = fMessage.userJid
        }
        val waContact = WaContactWpp.getWaContactFromJid(jidAuthor)

        val name = waContact?.displayName
            ?: jidAuthor.phoneNumber

        return if (jidAuthor.isGroup) {
            var participantJid = fMessage.userJid
            if (participantJid.isNull) {
                val deletedAdminUser = ReflectionUtils.getObjectField(fMessage.getObject(), "A00")
                if (deletedAdminUser != null) {
                    participantJid = FMessageWpp.UserJid(deletedAdminUser)
                }
            }
            val participantWaContact = WaContactWpp.getWaContactFromJid(participantJid)

            val participantName = participantWaContact?.displayName
                ?: participantJid.phoneNumber

            Utils.application
                .getString(R.string.deleted_a_message_in_group, participantName, name)
        } else {
            "$name $messageSuffix"
        }
    }

    private fun handleRevocationAlert(fMessage: FMessageWpp) {
        val message = formatRevocationMessage(fMessage) ?: return

        val jidAuthor = fMessage.key.remoteJid
        val actualAuthor = if (jidAuthor.isStatus) fMessage.userJid else jidAuthor
        val waContact = WaContactWpp.getWaContactFromJid(actualAuthor)

        val name = waContact?.displayName ?: actualAuthor.phoneNumber

        val taskerAction = if (jidAuthor.isStatus) "deleted_status" else "deleted_message"

        if (xprefs.getBoolean("toastdeleted", false)) {
            Utils.showToast(message, Toast.LENGTH_LONG)
        }

        Tasker.sendTaskerEvent(name, jidAuthor.phoneNumber, taskerAction)
    }

    override fun getPluginName(): String = "Anti Revoke"
}
