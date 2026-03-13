# duominecraftauth

A `Velocity` proxy plugin that requires selected Minecraft accounts to pass Duo 2FA before they can join backend servers.

This project is intended for setups like:

- `Velocity` as the public entry point
- `backend` servers behind the proxy
- admin / OP accounts protected with Duo Push

## Features

- Intercepts protected users during `PreLoginEvent`
- Uses Duo Auth API for `preauth`, `auth`, and `auth_status`
- Supports Duo Push verification
- Supports manual protected-player lists
- Supports importing protected usernames from backend `ops.json`
- Supports `Minecraft username -> Duo username` bindings
- Supports `/duoreload` for config reloads

## Why Proxy-Side 2FA

If Duo checks only happen on the backend server, a player has already passed the proxy layer. This plugin keeps the access decision at the network edge, where it belongs.

## Requirements

- Java 21+ runtime
- Velocity 3.4+ or 3.5+
- A Duo `Auth API` application

## Build

```powershell
.\gradlew.bat build
```

The built jar will be created in:

```text
build/libs/duominecraftauth-1.0.0.jar
```

## Installation

1. Copy the built jar into your Velocity `plugins/` folder.
2. Start Velocity once.
3. Edit the generated config file:

```text
plugins/DuoMinecraftAuth/duominecraftauth.properties
```

4. Restart the proxy or run `/duoreload`.

## Duo Setup

This plugin uses a Duo `Auth API` application.

In the Duo admin panel:

1. Open `Applications`
2. Click `Add application`
3. Search for `Partner Auth API`
4. Create a new `Partner Auth API` app
5. Copy:

- `API hostname`
- `Integration key`
- `Secret key`

These values go directly into the plugin config.

## Configuration

Example config:

```properties
duo.apiHost=api-xxxxxxxx.duosecurity.com
duo.integrationKey=DIXXXXXXXXXXXXXXXXXX
duo.secretKey=replace-me
duo.timeoutSeconds=65
duo.failOpen=false

security.protectedPlayers=jeb_
security.opsFile=

bindings.jeb_=JensBergensten

messages.prefix=[Duo]
messages.denied=You must approve the Duo push before joining.
messages.timeout=Duo verification timed out. Please try again.
messages.failed=Duo verification failed.
messages.error=Duo is currently unavailable. Contact an admin.
messages.enroll=Duo enrollment is required before this account can log in.
```

### Core Fields

`duo.apiHost`

- Duo `API hostname`

`duo.integrationKey`

- Duo `Integration key`

`duo.secretKey`

- Duo `Secret key`

`duo.timeoutSeconds`

- How long to wait for a Duo Push approval

`duo.failOpen`

- `false`: deny login if Duo is unavailable
- `true`: allow login if Duo is unavailable

For admin accounts, `false` is strongly recommended.

## Protecting Players

There are two ways to decide who requires Duo.

### 1. Manual List

```properties
security.protectedPlayers=AncientX,AnotherAdmin
```

### 2. Import From `ops.json`

```properties
security.opsFile=C:/Servers/fabric/ops.json
```

Notes:

- Forward slashes are recommended
- If the file is not loaded correctly, startup logs will show `0 protected op(s)`

## Duo Username Bindings

If a player's Minecraft name does not match their Duo username, use a binding:

```properties
bindings.AncientX=silent1893
```

Format:

```properties
bindings.<minecraftName>=<duoUsername>
```

If both names are the same, no binding is needed.


## Login Flow

When a protected account connects:

1. Velocity intercepts the login
2. The plugin calls Duo `preauth`
3. If allowed, the plugin starts a Duo Push request
4. The plugin polls the authentication result
5. The player is allowed in only after approval
6. Denial, timeout, or errors disconnect the player

## Commands

`/duoreload`

- Reloads `duominecraftauth.properties`
- Permission: `duominecraftauth.reload`

## Troubleshooting

### Player joins without Duo

Check:

- The player is listed in `security.protectedPlayers`
- Or the player exists in the backend `ops.json`
- The `ops.json` path is correct
- The username binding is correct

### `bindings` exists but Duo still does not trigger

`bindings` only maps names.  
It does not mark a player as protected.

### `0 protected op(s)` appears in logs

Usually means one of these:

- `security.opsFile` path is wrong
- The path used backslashes that were parsed badly
- `ops.json` is missing or malformed

### User gets enrollment or failure errors

The Duo user must already exist and have a registered device.  
This plugin currently assumes the Duo account is already provisioned.

### Can the Minecraft "Connecting to server..." screen show Duo instructions?

Not directly.  
That screen is controlled by the Minecraft client. If you want better in-game messaging, the best approach is a dedicated verification lobby server.

## Security Notes

- Backend servers should only accept connections from Velocity
- Never share your Duo `Secret key`
- If credentials are exposed, rotate the Duo `Secret key` immediately
- For admin accounts, keep `duo.failOpen=false`

## Project Structure

- `build.gradle`
- `settings.gradle`
- `src/main/java/com/artemislunar/duoauth/DuoMinecraftAuthPlugin.java`
- `src/main/java/com/artemislunar/duoauth/DuoAuthClient.java`
- `src/main/java/com/artemislunar/duoauth/PluginConfig.java`
- `src/main/resources/duominecraftauth.properties`

## Future Ideas

- Better localized messages
- Automatic `ops.json` hot reload
- Verification lobby mode
- Enrollment flow 