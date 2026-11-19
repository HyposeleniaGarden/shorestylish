# ShoreStylish

Server-side Fabric mod for Minecraft 26.3. It supports MiniMessage and Minecraft's legacy colour/style codes with `&` instead of `§`, plus player-set nicknames.

The first startup creates `config/shorestylish.json`:

```json
{
  "enable_message_format": true,
  "enable_chat_format": true,
  "chat_format": "&7%username%: &f%chatmessage%",
  "enable_nicknames": true,
  "nickname_permission": "EVERYONE"
}
```

Set `nickname_permission` to `OP` to require permission level 2 for `/nick`. `/nick` shows nickname help, `/nick <nickname>` sets the caller's nickname, and `/nick reset` resets it. Operators can reset an online player's nickname with `/nick reset <playername>`. Nicknames and messages accept MiniMessage (such as `<gold>Hello</gold>`) and ampersand codes (such as `&6Hello`).

`chat_format` controls the chat layout. It supports MiniMessage, ampersand colour/style codes, `%username%`, and `%chatmessage%`. Set `enable_chat_format` to `false` to use Minecraft's normal layout for plain messages; messages containing formatting still use ShoreStylish when `enable_message_format` is enabled.
