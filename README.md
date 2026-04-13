# Thready

A Wire bot that bridges Jira and Wire — posts live board updates into channels and lets users comment back on tickets without leaving Wire.

---

## Jira Integration Setup

Before running, you need:

**1. Environment variables**
```bash
export JIRA_EMAIL=your-jira-email@company.com
export JIRA_API_TOKEN=your-jira-api-token
```
Generate a token at https://id.atlassian.com/manage-profile/security/api-tokens

**2. ngrok (for local development)**
```bash
ngrok http 8080
```
Copy the `https://xxxx.ngrok-free.app` URL.

**3. Jira webhook**
- Go to your Jira project → Settings → Webhooks
- URL: `https://xxxx.ngrok-free.app/jira/webhook`
- Events: Issue (created, updated, deleted), Comment (created, updated), Sprint (started, closed)

**4. Enable in Wire**
Add the bot to a channel, then type:
```
/jira on
```

That's it. Jira updates will appear in the channel with a **💬 Add Comment** button. Clicking it opens a DM where you type your comment — the bot posts it to Jira on your behalf.

---

## Commands

### Jira
| Command | Description |
|---|---|
| `/jira on` | Enable Jira WPB board updates in this channel |
| `/jira off` | Disable Jira WPB board updates in this channel |

### Conversations
| Command | Description |
|---|---|
| `/group "Title" @users` | Create a group conversation |
| `/channel "Name" @users` | Create a channel |
| `/status` | List members, roles, and Jira update status |
| `/admin @user` | Promote user to admin |
| `/admin all` | Promote all members to admin |
| `/demote` | Demote all admins (excluding invoker and bot) |
| `/remove` | Remove all members (excluding invoker and bot) |
| `/delete` | Delete the current conversation |

### Direct Messaging
| Command | Description |
|---|---|
| `/dm @user message` | Send a DM to a specific user |
| `/dmall message` | Send a DM to all members |
| `/dmpingall` | Ping all members via DM |

---

## How Comment Flow Works

1. Jira update arrives → bot posts message with **💬 Add Comment** button
2. User clicks button → bot opens a DM: *"Type your comment for [WPB-123]"*
3. User types comment → bot posts to Jira as `[Name via Thready]: comment text`
4. Bot confirms: *"✅ Comment posted to [WPB-123]"*

Type `/cancel` in the DM to cancel at any step.

---

## Running

```bash
export JIRA_EMAIL=your-email
export JIRA_API_TOKEN=your-token
./gradlew run
```

---

## Architecture

```
Main.kt
├── main()                      Wire SDK + Jira webhook server bootstrap
├── startJiraWebhookServer()    Java HTTP server on port 8080
├── buildJiraEvent()            Parses Jira webhook payload → JiraEvent
├── postJiraComment()           Posts comment to Jira REST API
└── ThreadHandler
    ├── broadcastJiraUpdate()   Sends Composite message with button to all channels
    ├── onButtonClicked()       Handles comment button → opens DM flow
    ├── onTextMessageReceived() Command router + pending comment handler
    └── (conversation & DM management)
```

---

## Known Limitations

- Jira subscription (`/jira on`) resets on bot restart — re-run `/jira on` after restarting
- ngrok URL changes on every restart (free plan) — update Jira webhook URL each time
- No rate limiting for mass DM commands
- No retry mechanism for failed Jira API calls

---

## Author

Vinayak Sankar J
