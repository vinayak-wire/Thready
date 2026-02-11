🧵 Thready — A Thread-Creation Bot for Wire

Thready is a lightweight Kotlin bot built using the
wire-apps-jvm-sdk
.
It allows users to create new group conversations (“threads”) directly from any Wire conversation using simple slash commands.

Thready is ideal for:

Splitting discussions into new groups

Creating topic-based threads

Keeping conversations organised

Automating group creation workflows

🚀 Features
✔ /thread "Title" @UserA @UserB

Creates a new group conversation with the invoker + the mentioned users.

Example:

/thread "Release Planning" @anna @markus


➡️ Creates a new group “Release Planning” with Anna, Markus, and the command sender.

✔ /newthread

Alias for /thread.

⚠️ Notes

The title must be in quotes.

You must mention at least one user for /thread.

Admin-setting inside the created group is not supported yet (SDK limitation).

MLS (Message Layer Security) currently prevents the bot from sending immediate intro messages inside newly created groups.

📦 Project Structure
ThreadApp/
├── src/main/kotlin/
│    └── Main.kt          # Thready bot implementation
├── build.gradle.kts
└── README.md

🔧 Prerequisites

Kotlin/JVM 1.8+

Gradle (KTS)

A valid Wire API token

JVM SDK dependency:

implementation("com.wire:wire-apps-jvm-sdk:<latest-version>")

⚙️ Configuration

Set your values inside Main.kt:

val sdk = WireAppSdk(
applicationId = UUID.randomUUID(),
apiToken = "YOUR_API_TOKEN",
apiHost = "https://staging-nginz-https.zinfra.io",
cryptographyStoragePassword = "32_character_password_here",
wireEventsHandler = NewThreadHandler()
)

📝 Commands Summary
Command	Description
/thread "Topic" @user1 @user2	Creates a new group with the mentioned users
/newthread "Topic" @users	Same as /thread
/help	Shows usage instructions
🛠 How it Works

A text message is received by the bot.

If the message starts with /thread, Thready:

Extracts the title from quotes

Extracts all mentioned users

Creates a new group conversation via createGroupConversationSuspending

Replies in the original conversation confirming creation

All logic is event-driven using WireEventsHandlerSuspending.

📁 Example Output
/thread "Incident – VPN Down" @Alice @Bob

→ Thready:
✅ Created a new group "Incident – VPN Down" with the mentioned members.

🔐 MLS / SDK Limitations

Wire’s MLS rollout currently prevents:

Bots from immediately sending messages into newly created groups
(MLS group ID not yet available at creation time)

This means Thready can create groups but cannot automatically ping inside them yet.

The creation works reliably — only the autoping is disabled.

🧩 Future Enhancements

Planned improvements (SDK-limited today):

Auto-admin assignment

/thready "Topic" — create thread with all conversation members (requires SDK support)

Welcome message inside newly created groups (after MLS fix)

Metadata tagging for created threads