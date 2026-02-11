import com.wire.sdk.WireAppSdk
import com.wire.sdk.WireEventsHandlerSuspending
import com.wire.sdk.model.*
import com.wire.sdk.model.http.conversation.ConversationRole
import kotlinx.coroutines.delay
import java.util.UUID

/* ================= BOOTSTRAP ================= */

private const val DOMAIN = "staging.zinfra.io"
private val APP_UUID = UUID.fromString("41269e74-016a-4c59-a629-462f56656037")

fun getStorageKey(): ByteArray =
    ByteArray(32) { (it + 1).toByte() }

fun main() {
    val sdk = WireAppSdk(
        applicationId = APP_UUID,
        apiToken = "myApiToken",
        apiHost = "https://staging-nginz-https.zinfra.io",
        cryptographyStorageKey = getStorageKey(),
        ThreadHandler()
    )
    sdk.startListening()
}

/* ================= HANDLER ================= */

class ThreadHandler : WireEventsHandlerSuspending() {

    private val trackedMembers =
        mutableMapOf<String, MutableList<ConversationMember>>()

    private val appUserId =
        QualifiedId(id = APP_UUID, domain = DOMAIN)

    /* ============== EVENTS ================= */

    override suspend fun onAppAddedToConversation(
        conversation: Conversation,
        members: List<ConversationMember>
    ) {
        trackedMembers[conversation.id.toString()] = members.toMutableList()

        if (conversation.teamId != null) {
            forceChannelMemberHydration(conversation.id)
        }
    }

    override suspend fun onUserJoinedConversation(
        conversationId: QualifiedId,
        members: List<ConversationMember>
    ) {
        val memberList =
            trackedMembers.getOrPut(conversationId.toString()) { mutableListOf() }

        for (member in members) {
            val exists = memberList.any { it.userId == member.userId }
            if (!exists) memberList.add(member)
        }
    }

    override suspend fun onUserLeftConversation(
        conversationId: QualifiedId,
        members: List<QualifiedId>
    ) {
        trackedMembers[conversationId.toString()]
            ?.removeAll { it.userId in members }
    }

    /* ============== COMMAND ROUTER ================= */

    override suspend fun onTextMessageReceived(msg: WireMessage.Text) {

        val txt = msg.text.trim()
        if (!txt.startsWith("/")) return

        when (txt.substringBefore(" ").lowercase()) {
            "/help" -> reply(msg, help())
            "/status" -> handleStatus(msg)
            "/thread" -> createThread(msg)
            "/channel" -> handleChannel(msg)
            "/admin" -> handleAdmin(msg)
            "/demote" -> handleDemote(msg)
            "/remove" -> handleRemove(msg)
            "/dm" -> handleDm(msg)
            "/dmall" -> handleDmAll(msg)
            "/dmpingall" -> handleDmPingAll(msg)
            "/delete" -> handleDelete(msg)
        }
    }

    /* ============== HELP ================= */

    private fun help() = """
Commands:
/thread "Title" @users
/channel "Name" @users
/admin @user | /admin all
/demote @user | /demote all
/remove @user | /remove all
/dm @user <msg>
/dmall <msg>
/dmpingall
/status
/delete
""".trimIndent()

    /* ============== MEMBERS ================= */

    private suspend fun getMembersOrLoad(convId: QualifiedId): MutableList<ConversationMember> =
        trackedMembers[convId.toString()]
            ?: manager.getStoredConversationMembers(convId)
                .toMutableList()
                .also { trackedMembers[convId.toString()] = it }

    /* ============== STATUS ================= */

    private suspend fun handleStatus(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)

        reply(
            msg,
            buildString {
                appendLine("Members (${members.size}):")
                members.forEach { member ->
                    appendLine("• ${member.userId.id} | ${member.role}")
                }
            }
        )
    }

    /* ============== THREAD ================= */

    private suspend fun createThread(msg: WireMessage.Text) {
        val invoker = senderOf(msg) ?: return
        val title = msg.text.substringAfter("\"").substringBefore("\"").trim()
        if (title.isBlank()) return

        val convId = manager.createGroupConversationSuspending(
            title,
            listOf(invoker)
        ) ?: return

        manager.updateConversationMemberRoleSuspending(
            convId, invoker, ConversationRole.ADMIN
        )

        val others = allMentionedUsers(msg).filter { it != invoker }
        if (others.isNotEmpty()) {
            manager.addMembersToConversationSuspending(convId, others)
        }

        reply(msg, "✅ Group created.")
    }

    /* ============== CHANNEL ================= */

    private suspend fun handleChannel(msg: WireMessage.Text) {
        val invoker = senderOf(msg) ?: return
        val title = msg.text.substringAfter("\"").substringBefore("\"").trim()
        if (title.isBlank()) return

        val users = (listOf(invoker) + allMentionedUsers(msg)).distinct()

        val channelId = manager.createChannelConversationSuspending(
            name = title,
            userIds = emptyList()
        ) ?: return

        delay(1000)
        manager.addMembersToConversationSuspending(channelId, users)
        delay(1000)

        trackedMembers[channelId.toString()] =
            manager.getStoredConversationMembers(channelId).toMutableList()

        manager.updateConversationMemberRoleSuspending(
            channelId, invoker, ConversationRole.ADMIN
        )

        reply(msg, "✅ Channel created.")
    }

    /* ============== ADMIN ================= */

    private suspend fun handleAdmin(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)

        val targets =
            if (msg.text.contains("all", true))
                members.filter { it.role == ConversationRole.MEMBER }.map { it.userId }
            else
                allMentionedUsers(msg)

        targets.forEach {
            manager.updateConversationMemberRoleSuspending(
                msg.conversationId, it, ConversationRole.ADMIN
            )
            members.replace(it, ConversationRole.ADMIN)
        }

        reply(msg, "✅ Admin update complete.")
    }

    private suspend fun handleDemote(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)

        val targets =
            members.filter {
                it.role == ConversationRole.ADMIN &&
                        it.userId != invoker &&
                        it.userId != appUserId
            }.map { it.userId }

        targets.forEach {
            manager.updateConversationMemberRoleSuspending(
                msg.conversationId, it, ConversationRole.MEMBER
            )
            members.replace(it, ConversationRole.MEMBER)
        }

        reply(msg, "✅ Demoted ${targets.size} admins.")
    }

    private suspend fun handleRemove(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)

        val targets =
            members.map { it.userId }
                .filter { it != invoker && it != appUserId }

        if (targets.isEmpty()) {
            reply(msg, "ℹ️ No removable members.")
            return
        }

        manager.removeMembersFromConversationSuspending(msg.conversationId, targets)
        members.removeAll { it.userId in targets }

        reply(msg, "✅ Removed ${targets.size} members.")
    }

    /* ============== DM CORE ================= */

    private suspend fun getOrCreateDm(userId: QualifiedId): QualifiedId? {
        return runCatching {
            manager.createOneToOneConversationSuspending(userId)
        }.getOrElse {
            val conversations = manager.getStoredConversations()
            conversations.firstOrNull { conv ->
                val members = manager.getStoredConversationMembers(conv.id)
                members.size == 2 &&
                        members.any { it.userId == userId } &&
                        members.any { it.userId == appUserId }
            }?.id
        }
    }

    private suspend fun handleDm(msg: WireMessage.Text) {
        val target = allMentionedUsers(msg).firstOrNull() ?: return
        if (target == appUserId) return

        val text = msg.text.substringAfter(" ").substringAfter(" ").trim()
        if (text.isBlank()) return

        val dmId = getOrCreateDm(target) ?: return

        manager.sendMessageSuspending(
            WireMessage.Text.create(dmId, text)
        )
    }

    private suspend fun handleDmAll(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)
        val text = msg.text.substringAfter("/dmall").trim()
        if (text.isBlank()) return

        members.map { it.userId }
            .filter { it != invoker && it != appUserId }
            .distinct()
            .forEach { userId ->
                val dmId = getOrCreateDm(userId) ?: return@forEach
                manager.sendMessageSuspending(
                    WireMessage.Text.create(dmId, text)
                )
            }

        reply(msg, "📨 DM sent to all members.")
    }

    private suspend fun handleDmPingAll(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)

        members.map { it.userId }
            .filter { it != invoker && it != appUserId }
            .distinct()
            .forEach { userId ->
                val dmId = getOrCreateDm(userId) ?: return@forEach
                manager.sendMessage(
                    WireMessage.Ping.create(dmId)
                )
            }

        reply(msg, "🔔 Pinged everyone in DM.")
    }

    /* ============== DELETE ================= */

    private suspend fun handleDelete(msg: WireMessage.Text) {
        manager.deleteConversationSuspending(msg.conversationId)
        trackedMembers.remove(msg.conversationId.toString())
        reply(msg, "🗑️ Conversation deleted.")
    }

    /* ============== CHANNEL HYDRATION ================= */

    private suspend fun forceChannelMemberHydration(channelId: QualifiedId) {
        delay(2000)
        val members = manager.getStoredConversationMembers(channelId)
        val recycle = members.filter { it.userId != appUserId }.map { it.userId }
        if (recycle.isEmpty()) return

        manager.removeMembersFromConversationSuspending(channelId, recycle)
        delay(1500)
        manager.addMembersToConversationSuspending(channelId, recycle)
    }

    /* ============== HELPERS ================= */

    private fun MutableList<ConversationMember>.replace(
        id: QualifiedId,
        role: ConversationRole
    ) {
        indexOfFirst { it.userId == id }
            .takeIf { it >= 0 }
            ?.let { this[it] = this[it].copy(role = role) }
    }

    private suspend fun reply(msg: WireMessage.Text, text: String) {
        manager.sendMessageSuspending(
            WireMessage.Text.create(msg.conversationId, text)
        )
    }

    private fun senderOf(msg: WireMessage.Text): QualifiedId? =
        listOf("sender", "senderId", "userId", "authorId", "from")
            .firstNotNullOfOrNull {
                runCatching {
                    msg::class.java.getDeclaredField(it)
                        .apply { isAccessible = true }
                        .get(msg) as? QualifiedId
                }.getOrNull()
            }

    private fun allMentionedUsers(msg: WireMessage.Text): List<QualifiedId> =
        msg.mentions?.mapNotNull { m ->
            listOf("userId", "qualifiedId", "id")
                .firstNotNullOfOrNull {
                    runCatching {
                        m::class.java.getDeclaredField(it)
                            .apply { isAccessible = true }
                            .get(m) as? QualifiedId
                    }.getOrNull()
                }
        } ?: emptyList()
}
