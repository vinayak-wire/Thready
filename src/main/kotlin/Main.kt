import com.wire.sdk.WireAppSdk
import com.wire.sdk.WireEventsHandlerSuspending
import com.wire.sdk.model.*
import com.wire.sdk.model.http.conversation.ConversationRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID

/* ================= BOOTSTRAP ================= */

private const val DOMAIN = "staging.zinfra.io"
private val APP_UUID = UUID.fromString("41269e74-016a-4c59-a629-462f56656037")

private const val JIRA_PROJECT_KEY  = "WPB"
private const val JIRA_BOARD_ID     = 488
private const val WEBHOOK_PORT      = 8080

// Jira REST API credentials — used to post comments back to Jira
private const val JIRA_BASE_URL     = "https://wearezeta.atlassian.net"
private val JIRA_EMAIL     = System.getenv("JIRA_EMAIL") ?: ""
private val JIRA_API_TOKEN = System.getenv("JIRA_API_TOKEN") ?: ""

fun getStorageKey(): ByteArray =

    ByteArray(32) { (it + 1).toByte() }

fun main() {
    val handler = ThreadHandler()
    startJiraWebhookServer(handler)

    val sdk = WireAppSdk(
        applicationId = APP_UUID,
        apiToken = "myApiToken",
        apiHost = "https://staging-nginz-https.zinfra.io",
        cryptographyStorageKey = getStorageKey(),
        handler
    )
    sdk.startListening()
}

/* ================= JIRA REST API ================= */

/**
 * Posts a comment to a Jira issue via the REST API.
 * The comment is attributed to the Wire user's display name.
 */
fun postJiraComment(issueKey: String, wireUserName: String, commentText: String): Boolean {
    val auth = Base64.getEncoder().encodeToString("$JIRA_EMAIL:$JIRA_API_TOKEN".toByteArray())
    val body = """{"body": "[$wireUserName via Thready]: $commentText"}"""

    return runCatching {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$JIRA_BASE_URL/rest/api/2/issue/$issueKey/comment"))
            .header("Authorization", "Basic $auth")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        println("Jira comment response: ${response.statusCode()}")
        response.statusCode() in 200..299
    }.getOrElse { e ->
        println("Failed to post Jira comment: ${e.message}")
        false
    }
}

/* ================= JIRA WEBHOOK SERVER ================= */

fun startJiraWebhookServer(handler: ThreadHandler) {
    val server = com.sun.net.httpserver.HttpServer.create(
        java.net.InetSocketAddress(WEBHOOK_PORT), 0
    )
    server.createContext("/jira/webhook") { exchange ->
        val body = exchange.requestBody.bufferedReader().readText()
        val response = "OK"
        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.write(response.toByteArray())
        exchange.responseBody.close()

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val json = Json.parseToJsonElement(body).jsonObject
                val event = buildJiraEvent(json)
                if (event != null) {
                    handler.broadcastJiraUpdate(event)
                }
            }.onFailure { e ->
                println("Jira webhook error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    println("Server started on port $WEBHOOK_PORT")
}

/* ================= JIRA EVENT MODEL ================= */

/**
 * Structured Jira event — carries both the display text and the issue key
 * so we can reference the issue later when posting a comment.
 */
data class JiraEvent(
    val issueKey: String,   // e.g. "WPB-123" — null for sprint events
    val text: String        // Human-readable summary to post in Wire
)

fun buildJiraEvent(json: JsonObject): JiraEvent? {
    val webhookEvent = json["webhookEvent"]?.jsonPrimitive?.content ?: return null

    val projectKey   = json["issue"]?.jsonObject?.get("fields")
        ?.jsonObject?.get("project")?.jsonObject?.get("key")?.jsonPrimitive?.content
    val sprintBoardId = json["sprint"]?.jsonObject?.get("originBoardId")?.jsonPrimitive?.intOrNull

    if (projectKey != JIRA_PROJECT_KEY && sprintBoardId != JIRA_BOARD_ID) return null

    return when (webhookEvent) {

        "jira:issue_created" -> {
            val issue    = json["issue"]?.jsonObject ?: return null
            val key      = issue["key"]?.jsonPrimitive?.content ?: "?"
            val fields   = issue["fields"]?.jsonObject ?: JsonObject(emptyMap())
            val summary  = fields["summary"]?.jsonPrimitive?.content ?: "(no title)"
            val type     = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Issue"
            val assignee = fields["assignee"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content ?: "Unassigned"
            val priority = fields["priority"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "?"
            JiraEvent(
                issueKey = key,
                text = "🆕 New $type: [$key] $summary\n👤 $assignee  |  🔺 $priority\n🔗 $JIRA_BASE_URL/browse/$key"
            )
        }

        "jira:issue_updated" -> {
            val issue   = json["issue"]?.jsonObject ?: return null
            val key     = issue["key"]?.jsonPrimitive?.content ?: "?"
            val fields  = issue["fields"]?.jsonObject ?: JsonObject(emptyMap())
            val summary = fields["summary"]?.jsonPrimitive?.content ?: "(no title)"
            val changes = json["changelog"]?.jsonObject?.get("items")?.jsonArray
                ?.joinToString("\n") { item ->
                    val o     = item.jsonObject
                    val field = o["field"]?.jsonPrimitive?.content ?: "?"
                    val from  = o["fromString"]?.jsonPrimitive?.content ?: "-"
                    val to    = o["toString"]?.jsonPrimitive?.content ?: "-"
                    "  • $field: $from → $to"
                } ?: "  (no details)"
            JiraEvent(
                issueKey = key,
                text = "✏️ Issue updated: [$key] $summary\n$changes\n🔗 $JIRA_BASE_URL/browse/$key"
            )
        }

        "jira:issue_deleted" -> {
            val issue   = json["issue"]?.jsonObject ?: return null
            val key     = issue["key"]?.jsonPrimitive?.content ?: "?"
            val summary = issue["fields"]?.jsonObject?.get("summary")?.jsonPrimitive?.content ?: "(no title)"
            JiraEvent(issueKey = key, text = "🗑️ Issue deleted: [$key] $summary")
        }

        "comment_created", "comment_updated" -> {
            val issue   = json["issue"]?.jsonObject ?: return null
            val key     = issue["key"]?.jsonPrimitive?.content ?: "?"
            val summary = issue["fields"]?.jsonObject?.get("summary")?.jsonPrimitive?.content ?: "(no title)"
            val comment = json["comment"]?.jsonObject ?: return null
            val author  = comment["author"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content ?: "Someone"
            val body    = comment["body"]?.jsonPrimitive?.content?.take(200)
                ?.let { if (it.length == 200) "$it…" else it } ?: ""
            val verb    = if (webhookEvent == "comment_created") "commented on" else "updated a comment on"
            JiraEvent(
                issueKey = key,
                text = "💬 $author $verb [$key] $summary\n\"$body\"\n🔗 $JIRA_BASE_URL/browse/$key"
            )
        }

        "sprint_started" -> {
            val sprint = json["sprint"]?.jsonObject ?: return null
            val name   = sprint["name"]?.jsonPrimitive?.content ?: "Sprint"
            val goal   = sprint["goal"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?.let { "\nGoal: $it" } ?: ""
            JiraEvent(issueKey = "", text = "🚀 Sprint started: $name$goal")
        }

        "sprint_closed" -> {
            val sprint     = json["sprint"]?.jsonObject ?: return null
            val name       = sprint["name"]?.jsonPrimitive?.content ?: "Sprint"
            val completed  = json["completedIssues"]?.jsonArray?.size ?: 0
            val incomplete = json["incompletedIssues"]?.jsonArray?.size ?: 0
            JiraEvent(issueKey = "", text = "🏁 Sprint closed: $name\n✅ Completed: $completed  |  🔄 Carried over: $incomplete")
        }

        else -> null
    }
}

/* ================= HANDLER ================= */

class ThreadHandler : WireEventsHandlerSuspending() {

    private val trackedMembers      = mutableMapOf<String, MutableList<ConversationMember>>()
    private val activeConversationIds = mutableSetOf<QualifiedId>()
    private val appUserId           = QualifiedId(id = APP_UUID, domain = DOMAIN)

    // Maps button ID → issue key, so onButtonClicked knows which issue to comment on
    // Maps DM conversation ID → issue key, so we know where to post the comment
    private val buttonIssueMap = mutableMapOf<String, String>()  // buttonId  → issueKey
    private val pendingComment = mutableMapOf<String, String>()  // dmConvId  → issueKey

    /* ============== JIRA BROADCAST ================= */

    /**
     * Sends a Composite message (text + "💬 Add Comment" button) to all active channels.
     * Stores the button ID → issue key mapping so onButtonClicked can look it up.
     */
    suspend fun broadcastJiraUpdate(event: JiraEvent) {
        activeConversationIds.forEach { convId ->
            runCatching {
                // Sprint events have no issue key — send plain text, no button
                if (event.issueKey.isBlank()) {
                    manager.sendMessageSuspending(WireMessage.Text.create(convId, event.text))
                    return@runCatching
                }

                val button = WireMessage.Button(text = "💬 Add Comment")
                buttonIssueMap[button.id] = event.issueKey

                val composite = WireMessage.Composite.create(
                    conversationId = convId,
                    text = event.text,
                    buttonList = listOf(button)
                )
                manager.sendMessageSuspending(composite)
            }.onFailure { e ->
                println("Failed to post to $convId: ${e.message}")
            }
        }
    }

    /* ============== BUTTON CLICKED ================= */

    override suspend fun onButtonClicked(wireMessage: WireMessage.ButtonAction) {
        val issueKey = buttonIssueMap[wireMessage.buttonId] ?: return
        val userId = wireMessage.sender
        val dmId = getOrCreateDm(userId) ?: return
        pendingComment[dmId.toString()] = issueKey

        val userInfo = runCatching { manager.getUserSuspending(userId) }.getOrNull()
        val userName = userInfo?.name ?: "there"

        manager.sendMessageSuspending(
            WireMessage.Text.create(
                dmId,
                "Hi $userName! Type your comment for [$issueKey] and I'll post it to Jira.\n" +
                "(Send /cancel to cancel)"
            )
        )
    }

    /* ============== EVENTS ================= */

    override suspend fun onAppAddedToConversation(
        conversation: Conversation,
        members: List<ConversationMember>
    ) {
        trackedMembers[conversation.id.toString()] = members.toMutableList()
        activeConversationIds.add(conversation.id)

        if (conversation.teamId != null) {
            forceChannelMemberHydration(conversation.id)
        }
    }

    override suspend fun onUserJoinedConversation(
        conversationId: QualifiedId,
        members: List<ConversationMember>
    ) {
        val memberList = trackedMembers.getOrPut(conversationId.toString()) { mutableListOf() }
        for (member in members) {
            if (memberList.none { it.userId == member.userId }) memberList.add(member)
        }
    }

    override suspend fun onUserLeftConversation(
        conversationId: QualifiedId,
        members: List<QualifiedId>
    ) {
        trackedMembers[conversationId.toString()]?.removeAll { it.userId in members }
    }

    /* ============== COMMAND ROUTER ================= */

    override suspend fun onTextMessageReceived(msg: WireMessage.Text) {
        val txt = msg.text.trim()

        // ── Check if this is a pending comment reply in a DM ─────────────────
        val issueKey = pendingComment[msg.conversationId.toString()]
        if (issueKey != null) {
            if (txt.lowercase() == "/cancel") {
                pendingComment.remove(msg.conversationId.toString())
                reply(msg, "Cancelled.")
                return
            }
            handlePendingComment(msg, issueKey, txt)
            return
        }

        // ── Normal command routing ────────────────────────────────────────────
        if (!txt.startsWith("/")) return

        when (txt.substringBefore(" ").lowercase()) {
            "/help"      -> reply(msg, help())
            "/status"    -> handleStatus(msg)
            "/group"     -> createGroup(msg)
            "/channel"   -> handleChannel(msg)
            "/admin"     -> handleAdmin(msg)
            "/demote"    -> handleDemote(msg)
            "/remove"    -> handleRemove(msg)
            "/dm"        -> handleDm(msg)
            "/dmall"     -> handleDmAll(msg)
            "/dmpingall" -> handleDmPingAll(msg)
            "/delete"    -> handleDelete(msg)
            "/jira"      -> handleJiraToggle(msg)
        }
    }

    /* ============== PENDING COMMENT HANDLER ================= */

    private suspend fun handlePendingComment(
        msg: WireMessage.Text,
        issueKey: String,
        commentText: String
    ) {
        pendingComment.remove(msg.conversationId.toString())

        // Get the commenter's display name
        val userInfo = runCatching { manager.getUserSuspending(msg.sender) }.getOrNull()
        val userName = userInfo?.name ?: "Wire User"

        reply(msg, "Posting your comment to [$issueKey]...")

        val success = CoroutineScope(Dispatchers.IO).run {
            postJiraComment(issueKey, userName, commentText)
        }

        if (success) {
            reply(msg, "✅ Comment posted to [$issueKey] in Jira!")
        } else {
            reply(msg, "❌ Failed to post comment. Please try again or check Jira directly.")
        }
    }

    /* ============== HELP ================= */

    private fun help() = """
Commands:
/group "Title" @users
/channel "Name" @users
/admin @user | /admin all
/demote @user | /demote all
/remove @user | /remove all
/dm @user <msg>
/dmall <msg>
/dmpingall
/status
/delete
/jira on  – receive Jira WPB board updates here
/jira off – stop Jira updates in this channel
""".trimIndent()

    /* ============== JIRA TOGGLE ================= */

    private suspend fun handleJiraToggle(msg: WireMessage.Text) {
        val arg = msg.text.substringAfter("/jira").trim().lowercase()
        when (arg) {
            "on"  -> {
                activeConversationIds.add(msg.conversationId)
                reply(msg, "✅ Jira WPB board updates enabled in this channel.")
            }
            "off" -> {
                activeConversationIds.remove(msg.conversationId)
                reply(msg, "🔕 Jira WPB board updates disabled in this channel.")
            }
            else  -> reply(
                msg,
                "Usage: /jira on  or  /jira off\n" +
                "Currently: ${if (msg.conversationId in activeConversationIds) "ON ✅" else "OFF 🔕"}"
            )
        }
    }

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
                members.forEach { appendLine("• ${it.userId.id} | ${it.role}") }
                appendLine()
                append("Jira WPB updates: ${if (msg.conversationId in activeConversationIds) "ON ✅" else "OFF 🔕"}")
            }
        )
    }

    /* ============== GROUP ================= */

    private suspend fun createGroup(msg: WireMessage.Text) {
        val invoker = senderOf(msg) ?: return
        val title = msg.text.substringAfter("\"").substringBefore("\"").trim()
        if (title.isBlank()) return

        val convId = manager.createGroupConversationSuspending(title, listOf(invoker)) ?: return
        manager.updateConversationMemberRoleSuspending(convId, invoker, ConversationRole.ADMIN)

        val others = allMentionedUsers(msg).filter { it != invoker }
        if (others.isNotEmpty()) manager.addMembersToConversationSuspending(convId, others)

        reply(msg, "✅ Group created.")
    }

    /* ============== CHANNEL ================= */

    private suspend fun handleChannel(msg: WireMessage.Text) {
        val invoker = senderOf(msg) ?: return
        val title = msg.text.substringAfter("\"").substringBefore("\"").trim()
        if (title.isBlank()) return

        val users = (listOf(invoker) + allMentionedUsers(msg)).distinct()
        val channelId = manager.createChannelConversationSuspending(name = title, userIds = emptyList()) ?: return

        delay(1000)
        manager.addMembersToConversationSuspending(channelId, users)
        delay(1000)

        trackedMembers[channelId.toString()] =
            manager.getStoredConversationMembers(channelId).toMutableList()
        manager.updateConversationMemberRoleSuspending(channelId, invoker, ConversationRole.ADMIN)

        reply(msg, "✅ Channel created.")
    }

    /* ============== ADMIN ================= */

    private suspend fun handleAdmin(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val targets = if (msg.text.contains("all", true))
            members.filter { it.role == ConversationRole.MEMBER }.map { it.userId }
        else allMentionedUsers(msg)

        targets.forEach {
            manager.updateConversationMemberRoleSuspending(msg.conversationId, it, ConversationRole.ADMIN)
            members.replace(it, ConversationRole.ADMIN)
        }
        reply(msg, "✅ Admin update complete.")
    }

    private suspend fun handleDemote(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)
        val targets = members.filter {
            it.role == ConversationRole.ADMIN && it.userId != invoker && it.userId != appUserId
        }.map { it.userId }

        targets.forEach {
            manager.updateConversationMemberRoleSuspending(msg.conversationId, it, ConversationRole.MEMBER)
            members.replace(it, ConversationRole.MEMBER)
        }
        reply(msg, "✅ Demoted ${targets.size} admins.")
    }

    private suspend fun handleRemove(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)
        val targets = members.map { it.userId }.filter { it != invoker && it != appUserId }

        if (targets.isEmpty()) { reply(msg, "ℹ️ No removable members."); return }

        manager.removeMembersFromConversationSuspending(msg.conversationId, targets)
        members.removeAll { it.userId in targets }
        reply(msg, "✅ Removed ${targets.size} members.")
    }

    /* ============== DM CORE ================= */

    private suspend fun getOrCreateDm(userId: QualifiedId): QualifiedId? {
        return runCatching {
            manager.createOneToOneConversationSuspending(userId)
        }.getOrElse {
            manager.getStoredConversations().firstOrNull { conv ->
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
        manager.sendMessageSuspending(WireMessage.Text.create(dmId, text))
    }

    private suspend fun handleDmAll(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)
        val text = msg.text.substringAfter("/dmall").trim()
        if (text.isBlank()) return

        members.map { it.userId }.filter { it != invoker && it != appUserId }.distinct()
            .forEach { userId ->
                val dmId = getOrCreateDm(userId) ?: return@forEach
                manager.sendMessageSuspending(WireMessage.Text.create(dmId, text))
            }
        reply(msg, "📨 DM sent to all members.")
    }

    private suspend fun handleDmPingAll(msg: WireMessage.Text) {
        val members = getMembersOrLoad(msg.conversationId)
        val invoker = senderOf(msg)

        members.map { it.userId }.filter { it != invoker && it != appUserId }.distinct()
            .forEach { userId ->
                val dmId = getOrCreateDm(userId) ?: return@forEach
                manager.sendMessage(WireMessage.Ping.create(dmId))
            }
        reply(msg, "🔔 Pinged everyone in DM.")
    }

    /* ============== DELETE ================= */

    private suspend fun handleDelete(msg: WireMessage.Text) {
        activeConversationIds.remove(msg.conversationId)
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

    private fun MutableList<ConversationMember>.replace(id: QualifiedId, role: ConversationRole) {
        indexOfFirst { it.userId == id }.takeIf { it >= 0 }
            ?.let { this[it] = this[it].copy(role = role) }
    }

    private suspend fun reply(msg: WireMessage.Text, text: String) {
        manager.sendMessageSuspending(WireMessage.Text.create(msg.conversationId, text))
    }

    private fun senderOf(msg: WireMessage.Text): QualifiedId? =
        listOf("sender", "senderId", "userId", "authorId", "from")
            .firstNotNullOfOrNull {
                runCatching {
                    msg::class.java.getDeclaredField(it).apply { isAccessible = true }.get(msg) as? QualifiedId
                }.getOrNull()
            }

    private fun allMentionedUsers(msg: WireMessage.Text): List<QualifiedId> =
        msg.mentions?.mapNotNull { m ->
            listOf("userId", "qualifiedId", "id").firstNotNullOfOrNull {
                runCatching {
                    m::class.java.getDeclaredField(it).apply { isAccessible = true }.get(m) as? QualifiedId
                }.getOrNull()
            }
        } ?: emptyList()
}
