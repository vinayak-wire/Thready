📜 Available Commands

All commands must start with /

🧵 Conversation Commands
/thread "Title" @users

Create a group conversation.

/channel "Name" @users

Create a channel conversation.

/status

List members and their roles.

/admin @user

Promote user to ADMIN.

/admin all

Promote all members to ADMIN.

/demote

Demote all eligible admins (excluding invoker + app).

/remove

Remove all removable members (excluding invoker + app).

/delete

Delete the current conversation.

💬 Direct Messaging Commands
/dm @user message

Send a direct message to a specific user.

/dmall message

Send a DM to all members in the conversation (excluding invoker and app).

/dmpingall

Ping all members in DM (no message).

/dmping message

🔥 Broadcast DM with message + Ping to all members.

This:

Creates (or reuses) 1:1 DM

Sends the message

Immediately sends a Ping

Use this for urgent notifications.

Example:

/dmping Urgent maintenance at 10PM

🧠 How DM Logic Works

When sending DMs:

First tries createOneToOneConversationSuspending

If creation fails (conversation already exists),

Falls back to searching stored conversations

Reuses existing 1:1 conversation

This prevents MLS handshake duplication issues.

🔐 Safety Rules

App never removes itself

App never demotes itself

Sender excluded from DM broadcast

DM logic avoids duplicate 1:1 creation failures

📂 Architecture Overview
Main.kt
 ├── WireAppSdk bootstrap
 ├── ThreadHandler (event listener)
 │     ├── Command router
 │     ├── Conversation management
 │     ├── DM management
 │     ├── Ping logic
 │     └── Member tracking cache

⚠️ Known Limitations

No rate limiting for mass DM

No batching for large teams

No retry mechanism for DM failures

No persistence beyond SDK storage

🧪 Recommended Improvements (Future)

Add Admin-only restrictions to broadcast commands

Add retry/backoff for failed DMs

Add summary report after broadcast

Add rate limiting for large teams

Add audit logging

📌 Production Advice

If used in real environments:

Protect broadcast commands behind admin-only checks

Add throttling to avoid backend rate limits

Log failures to external monitoring

Add command permissions model

👤 Author

Vinayak Sankar J
