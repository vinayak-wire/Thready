// Main.kt

import com.wire.sdk.WireAppSdk
import com.wire.sdk.WireEventsHandlerSuspending
import com.wire.sdk.model.QualifiedId
import com.wire.sdk.model.WireMessage
import java.util.UUID

fun main() {
    val sdk = WireAppSdk(
        applicationId = UUID.randomUUID(),
        apiToken = "myApiToken",                 // ← your real values
        apiHost = "https://staging-nginz-https.zinfra.io",
        cryptographyStoragePassword = "myDummyPasswordOfRandom32BytesCH",
        wireEventsHandler = NewThreadHandler()
    )
    sdk.startListening()
}

class NewThreadHandler : WireEventsHandlerSuspending() {

    override suspend fun onMessage(wireMessage: WireMessage.Text) {
        val txt = wireMessage.text.trim()
        if (!txt.startsWith("/")) return

        when (txt.substringBefore(' ').lowercase()) {
            "/help" -> reply(wireMessage, help())
            "/newthread", "/thread" -> createThread(wireMessage)
        }
    }

    private fun help() = """
Use these commands:

• /Thread "Discuss on Topic" @UserB @UserC
    → Create a **group** with you + mentioned users.

• /Thread Group "Discuss on Topic" @UserB @UserC
    → Same, but type is explicit.

Notes:
• Text inside quotes becomes the conversation name.
• You must mention at least one other user.
""".trimIndent()

    // ---------- /Thread (groups only) ----------

    private suspend fun createThread(msg: WireMessage.Text) {
        val invokerQ = senderOf(msg) ?: run {
            reply(msg, "⚠️ Could not resolve the invoker.")
            return
        }

        val fullText = msg.text.trim()

        // Drop the command itself: "/thread" or "/newthread"
        val rest = fullText.substringAfter(' ', missingDelimiterValue = "").trim()
        if (rest.isEmpty()) {
            reply(
                msg,
                "⚠️ Missing arguments.\n\n" +
                        "Example:\n" +
                        "• /Thread \"Discuss on Topic\" @UserB @UserC\n" +
                        "• /Thread Group \"Discuss on Topic\" @UserB @UserC"
            )
            return
        }

        // Determine if user wrote "Group" (otherwise default to group anyway)
        val afterKind: String = if (rest.lowercase().startsWith("group ")) {
            rest.substringAfter(' ', "").trim()
        } else {
            rest
        }

        // Expect a quoted title: "Discuss on Topic"
        if (!afterKind.startsWith("\"")) {
            reply(
                msg,
                "⚠️ Could not find a title in quotes.\n\n" +
                        "Example:\n/Thread \"Discuss on Topic\" @Someone"
            )
            return
        }
        val closingQuoteIdx = afterKind.indexOf('"', startIndex = 1)
        if (closingQuoteIdx == -1) {
            reply(
                msg,
                "⚠️ Title seems to be missing the closing quote.\n\n" +
                        "Example:\n/Thread \"Discuss on Topic\" @Someone"
            )
            return
        }

        val title = afterKind.substring(1, closingQuoteIdx).trim()

        // ---- Get participants from mentions ----
        val mentionedUsers = allMentionedUsers(msg)
            .filter { it != invokerQ }
            .distinctBy { it.toString() }

        if (mentionedUsers.isEmpty()) {
            reply(
                msg,
                "⚠️ Please mention at least one other user.\n\n" +
                        "Example:\n/Thread \"$title\" @Someone"
            )
            return
        }

        val members = listOf(invokerQ) + mentionedUsers

        val createdConversationId = try {
            manager.createGroupConversationSuspending(
                name = title,
                userIds = members
            )
        } catch (e: Throwable) {
            reply(
                msg,
                "⚠️ Could not create the group: ${e::class.simpleName}: ${e.message}"
            )
            return
        }

        if (createdConversationId != null) {
            // Intro message in the new group
            val introText =
                "This conversation was created by the App as per request for $invokerQ " +
                        "to manage the topic \"$title\"."

            runCatching {
                manager.sendMessage(
                    WireMessage.Text.create(
                        conversationId = createdConversationId,
                        text = introText
                    )
                )
            }

            // ✅ Success message in the original chat where the command was sent
            val membersText = members.joinToString(separator = ", ")
            reply(
                msg,
                "✅ Created a new group \"$title\" with mentioned members - We still need to work on making a user admin of a group , bear with us and until we implement it "
            )
        } else {
            reply(
                msg,
                "⚠️ The SDK did not return a conversation ID for the group."
            )
        }
    }

    // ---------- Send helpers ----------

    private fun reply(original: WireMessage.Text, text: String) {
        // Use a plain text message in the same conversation (no reply-quoting).
        runCatching {
            manager.sendMessage(
                WireMessage.Text.create(
                    conversationId = original.conversationId,
                    text = text
                )
            )
        }
    }

    // ---------- Extractors ----------

    private fun senderOf(msg: WireMessage.Text): QualifiedId? {
        for (n in listOf("sender", "senderId", "userId", "authorId", "from")) {
            try {
                val f = msg::class.java.getDeclaredField(n).apply { isAccessible = true }
                val v = f.get(msg)
                if (v is QualifiedId) return v
            } catch (_: Throwable) {}
        }
        return null
    }

    // All mentions → QualifiedId list
    private fun allMentionedUsers(msg: WireMessage.Text): List<QualifiedId> {
        val res = mutableListOf<QualifiedId>()
        val mentions = msg.mentions ?: return emptyList()
        val it = (mentions as? Iterable<*>)?.iterator() ?: return emptyList()
        while (it.hasNext()) {
            val m = it.next() ?: continue
            for (n in listOf("userId", "qualifiedId", "id")) {
                try {
                    val f = m::class.java.getDeclaredField(n).apply { isAccessible = true }
                    val v = f.get(m)
                    if (v is QualifiedId) {
                        res += v
                        break
                    }
                } catch (_: Throwable) {}
            }
        }
        return res
    }

    // Optional: UUID extraction kept for future logging / features
    @Suppress("unused")
    private fun qidToUuid(q: QualifiedId?): UUID? {
        if (q == null) return null
        runCatching {
            val f = q::class.java.getDeclaredField("id").apply { isAccessible = true }
            when (val v = f.get(q)) {
                is UUID -> return v
                is String -> return UUID.fromString(v)
            }
        }
        runCatching {
            val m = q::class.java.methods.firstOrNull { it.name.equals("getId", true) && it.parameterCount == 0 }
            when (val v = m?.invoke(q)) {
                is UUID -> return v
                is String -> return UUID.fromString(v)
            }
        }
        return null
    }
}
