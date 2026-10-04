# dsh-tabs for Android

English | [中文](#中文)

A phone client for **DeepSeek Harness (DSH)** instances running on *other* machines,
reached over SSH. One in-process SSH session per machine, one WebView per machine,
and a tab bar that keeps several of them open at once.

**It does not run a Harness.** There is no local tab, no embedded Node, and no `dsh`
on the device. Every tab is a machine somewhere else — which is also why this client
is simpler than the desktop one rather than a port of it.

---

## Quick start

1. **On the far machine:** an SSH server running, and `dsh` on `PATH` installed with
   Node. The packaged Desktop application does *not* count: it ships its Harness
   inside `app.asar` and installs no `dsh` command.
2. **On the phone:** install the app, tap **`+`**, fill in host, user and — until the
   app's key is on that machine — a password.
3. **Tap the tab** to connect. The Harness's web interface opens in the tab.

Do that once per machine and the tab bar remembers them.

## What it does

Per machine, over a single SSH connection:

1. Runs `dsh web --no-open --port 0` on the far side, so the *remote* OS picks a free
   port and a connect can never collide with a server someone started by hand.
2. Reads the port and session token back off the readiness line it prints.
3. Forwards a loopback port on the phone to that remote port and points a WebView at
   it.

The token is a one-time entry ticket: the server answers with a signed cookie and a
redirect, so every later reload uses the cookie rather than the URL.

## Features

| | |
| --- | --- |
| **Several machines at once** | A tab per machine, each with its own SSH session, WebView and conversation. Switching tabs changes visibility and nothing else — the page is never reloaded, so a conversation is still there when you come back. |
| **No Harness on the phone** | Nothing is installed on the target machine and nothing runs locally. The app starts the far side's own `dsh web` and forwards its port. |
| **SSH key, or a password** | Generate the app's key once and paste one line into `authorized_keys`, or use a password for a one-off connection. A password is never stored. |
| **Host keys are pinned** | Trust on first use, then verified on every later connect. A changed host key is refused, and you are told, rather than silently accepted. |
| **Teardown that reaps** | Closing a tab stops the remote `dsh web` on the far side instead of leaving it holding the machine's resources. |
| **Survives leaving the app** | A foreground service keeps the connection alive while you are in another app. |
| **Automatic reconnect** | If the remote server goes away — a plugin edit restarts it, for instance — the tab says so and reconnects on its own, within a bounded number of attempts. |
| **A readable failure** | A connect that fails shows the far side's own words, not just "connection failed". |

## Requirements

**The machine you connect to:** an SSH server, and `dsh` on `PATH` installed with
Node. That is all — the app installs nothing there.

**The phone:** Android 14 (API 34) or newer. The floor is cryptography rather than
fashion: Ed25519 host keys and XDH key exchange are the modern defaults, and the
platform only gained both at API 33. Raising the floor is what lets the app carry no
crypto library of its own.

## Install

**From a release.** Download `dsh-tabs-android-<version>.apk` from
[Releases](https://github.com/OahzOb/dsh-tabs-android/releases) and install it. The
release notes carry the SHA-256 of what was published.

It is signed with the maintainer's own key, so it **cannot be installed over a build
from anyone else** — Android identifies an app by its signing key, and a mismatch is
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old one first, which takes the
device book and the app's SSH key with it.

If `adb install` reports `INSTALL_FAILED_USER_RESTRICTED` on a Xiaomi device, that is
MIUI blocking the *streamed* install path only; pushing the APK and installing from
the device works.

**From source.** You need JDK 17, the Android SDK with platform 36, and `adb`.
Gradle comes with the wrapper:

```
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Building it yourself means signing it with your own key, which is the right thing to
do if you intend to keep it. See "Building" and "Signing" below.

## Using it

- **`+`** adds a machine. It needs a host, an SSH user, and — until the app's key is
  on that machine — a password. A password is used for that connection and is
  **never stored**; a wrong one is asked for again rather than retried.
- **SSH key…** in the same dialog generates the app's own key and shows the line to
  paste into that machine's `authorized_keys`. Once a machine has it, connecting
  never asks for anything.
- **Tap a tab** to connect it, or to bring it back if it is already up.
- **`×`** disconnects. The tab stays, because a tab is a configured machine, not a
  transient view.
- **Long-press a tab** to edit or remove the machine.

## Documentation

The rest of this file is the engineering record: why the architecture is what it is,
what was measured and how, and the failures that shaped it. It is long on purpose —
each section is a decision that cost something to learn.

| | |
| --- | --- |
| [Why the architecture is what it is](#why-the-architecture-is-what-it-is) | No embedded Node, one connection rather than two, the teardown contract |
| [Layout](#layout) | Every file and what owns it |
| [Building](#building) | The toolchain, the wrapper, the helper scripts, previews and the emulator |
| [Signing](#signing-and-why-a-debug-install-is-not-good-enough) | Why a debug install is a security hole, not a convenience |
| [The app's own SSH key](#the-apps-own-ssh-key) | Where it lives and what protects it |
| [Diagnosing a connection](#diagnosing-a-connection) | What to look at when it will not connect |
| [What was verified, and how](#what-was-verified-and-how) | The verification table |
| [Known gaps](#known-gaps) | What it does not do yet |

---

<a id="中文"></a>
# dsh-tabs 安卓端（中文）

[English](#dsh-tabs-for-android) | 中文

在手机上连接**其它机器**上运行的 **DeepSeek Harness (DSH)**，走 SSH。每台机器一条
进程内 SSH 会话、一个 WebView，标签栏可以同时开着好几台。

**它本身不跑 Harness。** 没有本地标签页，不内嵌 Node，设备上也没有 `dsh`。每个标签页
都是别处的机器 —— 这也是它比桌面端简单、而不是桌面端的移植版的原因。

---

## 快速开始

1. **目标机器**：跑着 SSH 服务，并且 `PATH` 上有用 Node 安装的 `dsh`。**打包的桌面应用
   不算** —— 它的 Harness 装在 `app.asar` 里，不提供 `dsh` 命令。
2. **手机**：装上应用，点 **`+`**，填主机、SSH 用户，以及密码（在把那台机器加上本应用的
   公钥之前需要）。
3. **点标签页**连接，Harness 的网页界面就会在标签里打开。

每台机器做一次，之后标签栏会记住它们。

## 它能做什么

针对每台机器，只走**一条** SSH 连接：

1. 在对端执行 `dsh web --no-open --port 0` —— 让**远端**系统自己挑一个空闲端口，所以
   绝不会和谁手动起的服务撞端口。
2. 从它打印的就绪行里读回端口和会话 token。
3. 把手机上的一个回环端口转发到那个远端端口，让 WebView 打开它。

token 是一次性的入场券：服务端回一个签名 cookie 并重定向，之后每次刷新都走 cookie 而不是
URL。

## 功能

| | |
| --- | --- |
| **多台机器同时开** | 每台机器一个标签页，各自独立的 SSH 会话、WebView 和对话。切标签只改可见性、不重新加载页面，所以切回来对话还在。 |
| **手机上不装任何东西** | 目标机器上不装东西，本地也不跑东西。应用只是启动对端自己的 `dsh web` 并转发端口。 |
| **密钥或密码** | 生成一次应用自己的密钥，把一行粘进 `authorized_keys`；或者用密码做一次性连接。密码**从不保存**。 |
| **主机密钥固定** | 首次信任，之后每次连接都校验。主机密钥变了会被拒绝并告知，而不是默默接受。 |
| **关得干净** | 关标签页会停掉远端的 `dsh web`，不会留下进程占着那台机器的资源。 |
| **切出去也不断** | 前台服务让连接在你用别的应用时保持存活。 |
| **自动重连** | 远端服务消失时（比如改插件导致它重启），标签页会说原因并自行重连，重试次数有上限。 |
| **失败能读懂** | 连接失败会显示对端自己的原话，而不是只给一句"连接失败"。 |

## 环境要求

**被连的机器**：一个 SSH 服务，以及 `PATH` 上用 Node 安装的 `dsh`。就这些 —— 应用不在
那边装任何东西。

**手机**：Android 14（API 34）或更新。这个下限是密码学决定的，不是赶时髦：Ed25519 主机
密钥和 XDH 密钥交换是现在的默认值，而平台到 API 33 才同时具备两者。抬高下限正是这个应用
能不带自己的加密库的原因。

## 安装

**用 Release。** 从 [Releases](https://github.com/OahzOb/dsh-tabs-android/releases) 下载
APK 安装。

它用维护者自己的密钥签名，所以**无法覆盖安装别人的构建** —— Android 用签名密钥识别应用，
不匹配就会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。得先卸载旧版，而卸载会一并带走设备簿和
应用自己的 SSH 密钥。

小米设备上如果 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`，那只是 MIUI 挡了**流式
安装**这条路；先 push 再在设备上安装即可。

**从源码构建。** 需要 JDK 17、带 platform 36 的 Android SDK、以及 `adb`。Gradle 由 wrapper
自带：

```
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

自己构建就是用你自己的密钥签名 —— 如果你打算长期用，这是正确的做法。细节见下面的
"Building" 与 "Signing"。

## 用法

- **`+`** 添加机器。需要主机、SSH 用户，以及密码（在那台机器上有本应用公钥之前）。密码只
  用于这次连接、**从不保存**；密码错了会重新询问而不是反复重试。
- 同一个对话框里的 **SSH key…** 生成应用自己的密钥，并给出要粘进那台机器
  `authorized_keys` 的那一行。机器有了它之后，连接就不再问任何东西。
- **点标签页**连接，或把它（已经连着的）调到前面。
- **`×`** 断开。标签页保留 —— 标签页是一台配置好的机器，不是临时视图。
- **长按标签页**编辑或删除这台机器。

## 文档

本文件其余部分是工程记录：架构为什么是这样、实测了什么、怎么测的，以及塑造了它的那些失败。
它很长是有意的 —— 每一节都是一个花了代价才换来的决定。

| | |
| --- | --- |
| [Why the architecture is what it is](#why-the-architecture-is-what-it-is) | 不内嵌 Node、一条连接而非两条、teardown 契约 |
| [Layout](#layout) | 每个文件及其归属 |
| [Building](#building) | 工具链、wrapper、helper 脚本、预览与模拟器 |
| [Signing](#signing-and-why-a-debug-install-is-not-good-enough) | 为什么 debug 安装是安全漏洞而不是便利 |
| [The app's own SSH key](#the-apps-own-ssh-key) | 密钥存在哪、靠什么保护 |
| [Diagnosing a connection](#diagnosing-a-connection) | 连不上时该看什么 |
| [What was verified, and how](#what-was-verified-and-how) | 验证记录表 |
| [Known gaps](#known-gaps) | 目前还做不到什么 |

> 技术细节一律以英文原文为准，中文部分不重复翻译，避免两份说明逐渐说不到一起去。

---

# The engineering record

The sections below are in English only. They are the record of what was measured and
why each decision is what it is; translating them would create two descriptions that
drift apart, and the code and the tests only ever reference one.

## Why the architecture is what it is

### No embedded Node

The obvious way to reuse the desktop app's logic would be to run Node on the
phone. It cannot work, and the failure is total rather than partial:

> "The `child_process` and `cluster` modules are not available."
> — nodejs-mobile's own documentation

The desktop connection layer *is* `child_process.spawn` — spawn `ssh`, parse its
stdout, spawn a second `ssh -N -L`. Embedding Node would therefore reuse **none**
of it, while adding a Kotlin↔Node bridge, ~11 MB per ABI of `libnode.so`, and a
runtime that is still on Node 18 (end-of-life April 2025).

The second way — bundle an `ssh` binary and spawn it — is legal but worse: since
Android 10 a writable app directory is not executable at all, so the binary must
ship inside the APK's native library directory *and* force legacy packaging
app-wide, and it would arrive with no `/etc/passwd`, no `~/.ssh`, no
`known_hosts`, and no `ssh_config`.

So the ~400 lines were ported to Kotlin. Everything a JVM SSH library replaces
the `ssh` binary with is one in-process session — which makes the desktop app's
*two* connections per device collapse into one.

### One connection, not two

The desktop app opens two SSH connections per device: one to run the server, one
bare `ssh -N -L` for the forward. Both exist only because `ssh` is a program with
one job per invocation. `RemoteSession` asks a single JSch `Session` for an exec
channel *and* a local forward, so there is one connection and one thing to tear
down.

### The teardown contract is unchanged, and it is the load-bearing part

A remote command started over SSH does **not** reliably die when the connection
goes away. Measured on the desktop project: a plain `exec dsh web`, and even one
under `ssh -tt`, both survived the client being killed outright, leaving an
orphaned server holding the remote port.

The fix is to block the remote program on stdin instead. sshd hands it a pipe
that reaches EOF the moment the connection ends, however it ends, so the
backgrounded server is always reaped:

- POSIX: `dsh web … & child=$!; cat > /dev/null; kill $child; wait $child`
- Windows: `[Console]::In.ReadToEnd()` in a `try/finally` that tree-kills the shim

**This is why `RemoteSession.stop()` closes stdin first** and only then drops the
forward, the channel and the session. Closing the connection first would leave
the far side holding the port with nobody to tell it otherwise.

### The forward is the library's, not ours

`Session.setPortForwardingL(0, "127.0.0.1", remotePort)` is the same thing
`ssh -L` does, and port `0` asks the OS for a free port and returns it — the
desktop app has to allocate its own port and hope nothing takes it in the gap.

It would be a mistake to hand-roll this. A listener that accepts the WebView's
connection has to implement HTTP/1.1 correctly: persistent connections by
default, chunked framing, `Upgrade` for the WebSocket the Harness UI uses,
half-close, no double-chunking. JSch already bridges a loopback connection to a
`direct-tcpip` channel, which is the identical job with a well-tested
implementation.

### Host keys: trust on first use, then pin

**JSch's `StrictHostKeyChecking=no` is weaker than OpenSSH's `accept-new`, and
that is a security bug waiting to happen.** In `Session.doCheckHostKey` the
changed-key branch is only reached when the setting is `ask` or `yes` — so with
`no`, a *changed* host key is accepted silently. A rebuilt machine, or something
in the middle, would be trusted without a word.

`TrustOnFirstUse` implements the real thing: accept a key never seen before and
report it so it can be pinned, and refuse a key that differs from the pinned one
with an explanation. The pin lives in the device record.

### Why `minSdk 34`

Modern OpenSSH offers `ssh-ed25519` host keys and `curve25519-sha256` key exchange
first. Android's platform only gained Ed25519 signatures and XDH at API 33. The
target device runs API 36, so the floor is set where the platform already does part
of the work.

### BouncyCastle is not optional, and this was measured

JSch ships its Ed25519 implementation under `META-INF/versions/15/` in a
**multi-release JAR**, and Android's runtime does not implement those: it links the
base class, whose constructor is

```java
throw new UnsupportedOperationException("SignatureEd25519 requires Java15+");
```

Measured on the device, against a real OpenSSH 9.5 server offering only
`ssh-ed25519`, that produces:

```
Algorithm negotiation fail: algorithmName="server_host_key"
  jschProposal="ecdsa-sha2-…,rsa-sha2-…"   serverProposal="ssh-ed25519"
```

The awkward part is that the platform **does** have Ed25519 — the probe reports
`ED25519_SIGNATURE=true` on this device — but JSch cannot reach it, because the
class that would call it is the one Android refuses to load. So `bcprov-jdk18on` is
not a duplicate of platform crypto here; it is the only route JSch has. It must
also be **registered** as a provider at start-up, or the same handshake fails with
the library sitting unused on the classpath — a worse bug, because it looks like
the fix did nothing.

### Cleartext to loopback, and only to loopback

From API 28 the platform refuses cleartext by default, and WebView enforces it
per host, so a blocked navigation fails with no useful explanation. Two separate
mechanisms are in play and are easy to conflate:

- **Android's cleartext policy** — `res/xml/network_security_config.xml` keeps
  `cleartextTrafficPermitted="false"` as the base and re-opens exactly
  `127.0.0.1`, `localhost` and `::1`. Nothing else in the app may speak cleartext.
- **The web platform's "potentially trustworthy origin"** — `http://127.0.0.1` is
  a secure context by definition, so `ws://` from the page is not mixed content.
  This does **not** substitute for the policy above: a loopback page can be a
  secure context and still be refused.

## Layout

```
app/src/main/java/dev/dshtabs/
  Remote.kt          what to run on the far side — a port of the desktop app's remote.js
  RemoteSession.kt   one SSH session: run the server, forward a port, tear down
  HostKeys.kt        trust on first use, then pinning
  Device.kt          the machine record and its book
  MainActivity.kt    the tab bar and the one WebView
  SessionService.kt  keeps a session alive with the screen off
  Notifications.kt   the one notification this app posts
  App.kt             start-up: where the book lives, and the notification channel
```

`Remote.kt` is a **port**, and its comments were ported with it, because each one
records a failure that was paid for once already.

### The parity test, which did not exist until it found two differences

This README used to say that `../dsh-tabs/tools/parity.mjs` ran both clients over
the same inputs and failed on any difference. **That file was never written.** Two
implementations of one protocol were kept in step by nothing at all, so
`app/src/test/java/dev/dshtabs/RemoteParityTest.kt` was written to be the check
that was promised — and it immediately found two differences:

1. **The readiness pattern is equivalent but not byte-identical.** JavaScript's
   `RegExp.source` keeps an escape it does not need, so the desktop writes
   `http:\/\/` where Kotlin writes `http:[/]/`. Same pattern, same matches. The
   test compares them with redundant escapes stripped, which is what the claim
   should have been.
2. **The Windows program had genuinely diverged, and this client was ahead of it.**
   It resolved `node` rather than trusting the `.cmd` shim, reported what it
   resolved, and watched the child for 60 seconds, because the desktop's version
   hung forever when `dsh` exited without announcing a URL.

**The second difference is closed, and this paragraph is the correction of a claim
that stood here until the desktop caught up.** The desktop adopted all of it — node
resolution, the report of what it resolved, and the 60-second watchdog — so the two
programs are now **byte-identical**, which is what the parity test asserts today:

```
ok  the Windows program is byte-identical to the desktop client's
```

That makes this client *behind* nothing and *ahead* of nothing; it is the same
program written twice. Reading "this client is ahead" here would tell you the
desktop's Windows branch lacks a watchdog it has had since the commit that adopted
it, and the honest way to check is the test rather than this prose. The history is
kept because it is why the watchdog exists at all: the failure was found here
first.

Its baseline is the desktop's **published contract**, `../dsh-tabs/docs/contract.json`
— generated there and pinned by that repository's own suite — not a copy of whatever
the desktop code last printed. The test **reads that file out of the sibling
repository**, so nothing has to be copied and nothing can go stale; the two
repositories are kept under one parent (`dsh-workspace/`) partly so that is possible.
Changing the desktop contract fails these tests on the next run, which is the signal
to decide whether this client needs the same change.

A checkout of this repository on its own still works: the test falls back to a
`contract.json` in its own test resources, and when neither file exists it fails and
says which path it looked in rather than passing with nothing to compare.

```
gradle testDebugUnitTest      # 12 checks, on the build machine, no device needed
```

POSIX and Windows are both held to exact bytes. Windows used to be held to something
weaker — the fragments both clients shared had to be present in both, and each
addition this client had made had to still be there — because the two programs really
were different. They are not any more, and an equality check is strictly stronger
than a fragment list: a list can only prove that the pieces somebody thought to name
are still present, and both clients could hold the same broken program.

### The dot that stayed grey, and the class of bug behind it

A connected machine kept a **grey dot**. Nothing was wrong with the connection and
nothing was wrong with the state machine: `renderTabs` set `isSelected`, `isEnabled`
and `isActivated` on the dot from the tab's state, and `dot.xml` was a selector over
exactly those three states. **`tab.xml` named `@drawable/dot_idle` directly instead
of `@drawable/dot`**, so the background was a plain grey oval that read none of them.
`dot_running`, `dot_starting` and `dot_failed` existed as files, were referenced by
the selector, and could not be reached.

The reason it survived every check this project had is worth stating, because it
generalises: **a state list has no failure mode.** It compiles, it packages, it
renders, and it throws nothing. A selector whose view never receives the state it
selects on just shows its default colour for ever — and unlike the rest of this app's
bugs, no test on the build machine could see it, because nothing in the JVM test
source set can inflate a layout.

`TabDotResourcesTest` closes that hole, and it deliberately checks the **class**
rather than the instance. It reads the layout, the selector and `MainActivity` as
text and asserts that **the set of states the code sets is the set of states the
selector reads**. A test that only looked for the one wrong string would have caught
this bug and not the next one; comparing the two sets catches a state added on either
side alone, which is the shape of the mistake. Two guards keep the comparison honest:
a selector with no unconditional default item would draw nothing at all when no state
matches, and two empty sets are equal, so neither side may be empty.

## Building

**What you need:** JDK 17, the Android SDK with platform 36 and build-tools 36.0.0,
and `adb` on the `PATH`. Gradle itself is not required — `gradlew` brings its own.

The three variables the build reads are `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and
`JAVA_HOME`. An Android Studio install sets the first two for you. A shell that has
them can call `adb`, `sdkmanager` and `java` directly.

`tools\android-env.ps1` is a dot-sourceable form of the same thing, plus
`Test-AndroidEnv` to report what each tool reports, and `Get-AndroidDevice` to hand
back the attached device's serial. It reads defaults from the environment, so on a
normal install there is nothing to configure:

```powershell
. .\tools\android-env.ps1
Test-AndroidEnv
```

**One machine's layout, as an example rather than a requirement.** The machine this
was developed on keeps its toolchain unpacked in a directory of its own and names the
paths in `tools\android-env.local.ps1`, which git ignores:

```
<toolchain>\jdk17            Temurin JDK 17
<toolchain>\gradle-8.9       Gradle 8.9 (AGP 8.7.3 requires 8.9+)
<toolchain>\android-studio   Android Studio 2026.2.1 (Rabbit 1)
<toolchain>\scrcpy           scrcpy 4.1, live device mirroring
<android-sdk>                command-line tools, platform 36, build-tools 36.0.0
```

`<toolchain>` and `<android-sdk>` stand in for whatever directories that file names
on your machine. The real names are deliberately left out here, for the same reason
the file itself is ignored.

Those paths are on that machine's user `PATH` as well. Nothing in the build depends
on them: the local file exists so that no *tracked* file has to carry one machine's
directories.

### The wrapper and the distribution

`gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are all committed, and
`gradle-wrapper.properties` names the official distribution. That is what a clone
needs, and `.\gradlew.bat assembleDebug` is the entry point — prefer it to a
globally installed `gradle.bat`, because the wrapper pins the version to the
project. The distribution is extracted into `~\.gradle\wrapper\dists` on first use,
so the first build needs a network and later ones do not.

**The official URL is committed; a machine that cannot reach it overrides locally.**
TLS to `services.gradle.org` failed through this project's development proxy at the
time, while `dl.google.com`, `repo1.maven.org` and `github.com` all succeeded through
that same proxy, and `gradle wrapper` refused to write the URL at all: `Test of
distribution url … failed`. That is a property of one machine, not of the project, so
it does not belong in a tracked file — a pin to a path that exists on one machine is
not a default, it is a break waiting for the first person who clones this.

Gradle's wrapper reads `distributionUrl` from `gradle-wrapper.properties` and honours
**no environment variable** for it, so there is no override to set — an earlier version
of this section claimed `GRADLE_DISTRIBUTION_URL` did that, and it does not; the
wrapper jar contains no such name. The workarounds are therefore local by nature:

- **Leave the edit uncommitted.** Point `distributionUrl` at a zip you unpacked
  yourself, build, and never commit that line. `git update-index --skip-worktree
  gradle\wrapper\gradle-wrapper.properties` keeps it out of your diffs while the file
  keeps the portable value in the repository.
- **Pre-seed the cache instead of downloading.** The wrapper extracts into
  `~\.gradle\wrapper\dists\gradle-8.9-bin\<hash>\`, where `<hash>` is the MD5 of the
  `distributionUrl` string rendered in base 36, and it downloads nothing when the
  distribution is already there. The official URL hashes to
  `90cnw93cvbtalezasaz0blq0a`, so a local `gradle-8.9-bin.zip` copied to

  ```powershell
  $d = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a"
  New-Item -ItemType Directory -Path $d -Force | Out-Null
  Copy-Item <your>\gradle-8.9-bin.zip $d
  ```

  is unpacked on the next `.\gradlew.bat` with no network at all. This is the route to
  prefer, because it leaves every tracked file portable and touches nothing in the
  repository.

One packing detail bites either way: build the zip with `bsdtar`, **not**
`Compress-Archive`. The latter omits directory entries, and the wrapper's unzip then
fails with `Could not unzip … (The system cannot find the path specified)` on the
first file — `tar -a -c -f gradle-8.9-bin.zip gradle-8.9` from the parent directory
produces an archive Java can read. A correctly built `gradle-8.9-bin.zip` is
136,135,026 bytes over 305 entries and starts with a `gradle-8.9/` directory entry;
its SHA-256 is `A4004742E8DE74A6CB564CA792E0A228A2686AFF7EE628DC733799D0933F99A5`.
The official archive is 136,114,148 bytes — a few kilobytes smaller, because the two
were not produced by the same packer, so compare entry structure rather than size.

The wrapper jar is the one Gradle itself embeds, as entry `gradle-wrapper.jar`
inside `gradle-<ver>\lib\plugins\gradle-wrapper-main-<ver>.jar`. Merging
`gradle-wrapper-main` with `gradle-wrapper-shared` by hand does **not** work: the
result compiles nothing and dies on `NoClassDefFoundError:
org/gradle/cli/CommandLineParser`.

```
.\gradlew.bat assembleDebug
.\gradlew.bat assembleDebug --offline
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`local.properties` points at the SDK and is machine-specific, like
`tools\android-env.local.ps1`.

### The helper scripts

`tools\` wraps the loop so a change can be compiled, installed and *looked at*
in one command:

```
.\tools\build.ps1                          # compile only
.\tools\build.ps1 -Offline                 # from the Gradle cache, no network
.\tools\build.ps1 -Install                 # compile and push to the device
.\tools\build.ps1 -Install -Shot -Label 04-tabs   # …and capture a screenshot
.\tools\shot.ps1 -Label 05-empty            # capture the current screen
.\tools\shot.ps1 -NoLaunch                  # capture without launching the app
```

`shot.ps1` writes to `.shots\`, alongside a `<label>.small.png` scaled down for
sharing. It wakes the screen first, and **reports mean brightness** so a black
frame cannot pass silently — `screencap` succeeds on a sleeping or locked device
and returns a perfectly valid all-black PNG, which is exactly the failure that
wastes an afternoon.

One limit is worth knowing before it does: **a locked device cannot be
photographed.** Android will not composite app windows while the keyguard is up,
so the framebuffer is black however awake the panel is. `wm dismiss-keyguard`
only works when there is no secure lock. Unlock the device first, and keep it
unlocked for the duration of a capture run.

### Previews without a device

Android Studio carries `layoutlib.jar` (61 MB, in `plugins\design-tools\lib\`)
which renders layouts and Composables **on the host**, so the preview does not
need the device, an emulator, or an unlock. Launch the IDE with:

```
<toolchain>\android-studio\bin\studio64.exe
```

### The emulator

An AVD exists and is called `dsh_tablet` — Pixel Tablet, Android 15 (API 35),
`google_apis`, `x86_64`. It is registered in `~\.android\avd\` and Android Studio
picks it up as a run target without further setup.

```
emulator -avd dsh_tablet
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The image is `x86_64`, not `arm64-v8a`: the host is Intel, and an arm64 image
would need full instruction emulation, which is slow enough to be useless for
watching a WebView paint.

Four settings in `config.ini` were changed from the profile defaults, because the
defaults are wrong for this machine and for this app:

| key | default | set to | why |
| --- | --- | --- | --- |
| `hw.gpu.enabled` | `no` | `yes` | `no` forces software rendering; a WebView is then unwatchable |
| `hw.ramSize` | `2G` | `4G` | the host has 31.8 GB; 2G makes Android kill the session |
| `vm.heapSize` | `192M` | `512M` | the app's own heap, separate from `hw.ramSize` |
| `hw.keyboard` | `no` | `yes` | type on the PC instead of a soft keyboard |

**Running it needs WHPX, and WHPX is now active.** `HypervisorPlatform` and
`VirtualMachinePlatform` were enabled with `dism` and committed by a reboot.
Before it, `emulator -accel-check` exited 6; after it:

```
accel:
0
WHPX(10.0.26300) is installed and usable.
accel
```

Verified end to end: the AVD booted Android 15, `adb install` succeeded, the app
launched, and a screenshot came back at 2560x1600 with the UI drawn. Graphics run
on the host GPU — `GLES: Google (Intel), Android Emulator OpenGL ES Translator
(Intel(R) Arc(TM) B580 Graphics)`.

Two things about this produce misleading messages, and both cost time if
believed:

- `emulator -accel-check` reporting *"Android Emulator hypervisor driver is not
  installed"* names the **legacy AEHD driver**, which this setup does not use and
  does not need — with `HypervisorPlatform` enabled the emulator uses WHPX. That
  message appeared *after* the features were installed but *before* the reboot,
  when it was true for the wrong reason.
- **Feature package state does not prove the hypervisor is up.** Installing the
  features reports success immediately, but the packages sit at `Install Pending`
  (CBS `0x60`), not `Installed` (`0x70`), until the reboot commits them. The only
  reliable check is
  `(Get-CimInstance Win32_ComputerSystem).HypervisorPresent`, which must read
  `True`.

The `dism` and `bcdedit` commands need an elevated shell. Approval prompts are
disabled in the assistant's session, so elevation is done by hand:
`<toolchain>\enable-hyperv.cmd` and
`<toolchain>\set-hypervisor-launch.cmd` each re-launch themselves via
`Start-Process -Verb RunAs` and need one UAC click. The scheduled-task route
(`schtasks /create /rl HIGHEST`, or `Register-ScheduledTask` with
`RunLevel Highest`) does **not** work here — both fail with "Access is denied",
because creating a highest-run-level task is itself an administrator operation.
`bcdedit` also rejects the `{current}` alias on this machine's localised
Windows; enumerate with a bare `bcdedit /enum` instead. In the end
`hypervisorlaunchtype` needed no change — enabling the features set it.

## Signing, and why a debug install is not good enough

`assembleDebug` produces an APK with `android:debuggable` set, and that is not
cosmetic: it is what makes `CryptoProbe` reachable. The launcher activity is
exported, so any app on the device can start it with extras of its own choosing,
and on a debug build the probe answers. See "The probe is debug-only" for what those
extras can reach — this app's real private key is one of them.

```
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

The release signing key lives **outside** this repository, under a directory of its
own, pointed at by `%USERPROFILE%\.android-keys\<name>.jks` in `keystore.properties`.
That file — path, alias and passwords — is ignored by git. Both decisions are the same
one: a signing key that reaches a repository has to be treated as compromised, and the password beside
it makes that worse rather than better. The build works without the file; a missing
`keystore.properties` produces an unsigned release APK, exactly as before, instead
of failing.

Two details about `keystore.properties` that cost a build each:

- **`storeFile` is absolute.** AGP resolves a relative value against the Gradle
  *daemon's* working directory rather than the project root, so `../<keys-dir>/…`
  resolves to `~/.gradle/daemon/8.9/<keys-dir>/…` and the build fails with
  "keystore file not found" naming a path nobody wrote.
- **A different key is a different app.** Android identifies an app by its signing
  key, so a release build cannot update a debug install, or another machine's release
  install. It has to be uninstalled first — which takes the device book and the app's
  own SSH key with it. Back the book up first if the machines in it are more than a
  few taps to retype:

  ```
  adb shell run-as dev.dshtabs cat files/dsh-tabs.json > dsh-tabs.json
  ```

  That only works while a debuggable build is installed, which is the one moment the
  book is easy to read — worth doing *before* the switch, not after.

## The app's own SSH key

One key, generated on the phone, pasted into as many `authorized_keys` files as the
operator likes. Ed25519, because that is what modern OpenSSH offers first and
because it is short enough to read off a screen.

**It is generated by JSch, which works only because BouncyCastle is a dependency** —
the same finding that made the handshake work at all. `KeyPair.genKeyPair(jsch,
ED25519)` goes through exactly the class Android's runtime cannot load by itself, so
this feature and that dependency are one decision rather than two.

The private half is written with a passphrase, and the passphrase is a random value
kept in `EncryptedSharedPreferences`, whose master key lives in the Android
Keystore. So the key on disk is ciphertext and the thing that decrypts it is
hardware-backed where the device supports it. That is not a defence against someone
holding an unlocked, rooted phone — it is a defence against a key that outlives the
app, through a backup, a file manager, or a copy made while debugging.

Two overload bugs were paid for here, and they are worth naming because both produce
a failure that points at the wrong thing:

- `KeyPair.load(jsch, prvfile, pubfile)` — the second string is a **`.pub` file
  path**, not a passphrase. Passing a passphrase there makes JSch try to open a file
  *named after the passphrase* and fail with `FileNotFoundException` naming it. The
  fix is to read the bytes and use `load(jsch, prvkey, pubkey)`, then `decrypt()`.
- `JSch.addIdentity(name, prvfile, passphrase)` **does not exist**. The three-argument
  form is `(name, prvkey, pubkey)`, so a passphrase lands in the `pubkey` slot. The
  fix is the four-argument `(name, prvkey, pubkey, passphrase)`.

Both are the same mistake in different clothes: JSch overloads its arguments by
position with no type distinction, so a wrong call compiles and fails later in a way
that describes the wrong file.

**Replacing the key is deliberate.** The button says what it will cost — every
machine that already has the current public key will refuse this app until the new
one is pasted there — because an app cannot reach into those files to clean up after
itself, and pretending otherwise would be worse than saying so.

## Threading: the bug that killed every first connect

`RemoteSession.open` runs on `Dispatchers.IO`, and it reports progress through
callbacks. A callback that touches a view therefore runs on a worker thread, and
the process dies:

```
CalledFromWrongThreadException: Only the original thread that created a view
hierarchy can touch its views. Expected: main  Calling: DefaultDispatcher-worker-2
  at MainActivity.showTranscript(MainActivity.kt:431)
  at MainActivity$startSession$1… (MainActivity.kt:467)
  at RemoteSession$Companion$open$3… (RemoteSession.kt:123)
```

**Kotlin's dispatchers do not save you here**, and that is the part worth
remembering. They only switch threads when the *dispatcher* changes, so a plain
lambda invoked from inside `withContext(Dispatchers.IO)` stays on the worker no
matter what dispatcher the enclosing coroutine declared. Declaring a coroutine
scope is not a thread guarantee for the callbacks you hand to a library.

The rule that fixes it is in the code: every callback that reaches a view goes
through `onMain`, and `render`/`renderTabs` are annotated `@MainThread` so the
constraint is visible rather than inferred.

An uncaught exception is now written to `files/last-crash.txt` as well as logcat,
because a crash during a connection is the worst place to lose information — the
transcript panel that would have explained it never rendered, and logcat rotates.

## A remote that cannot start must not hang

The Windows branch launches the far side with `Start-Process`. When that produced
no process at all — measured against this project's own machine, where `dsh` exits
on a profile it cannot resolve — the program reached `[Console]::In.ReadToEnd()`
and waited, and the client waited for a readiness line that would never come. The
operator saw a connection that neither finished nor failed.

The program now watches the child for 60 seconds and writes to stderr if it dies
first, so the failure arrives as a transcript line:

```
! dsh exited with code  before announcing a URL
```

**The gap after `code` is not a typo in this document.** It is what the program
prints, and it is a PowerShell limitation rather than a decision: `Start-Process
-PassThru` leaves `$proc.ExitCode` unset unless it is also given `-Wait`, and `-Wait`
would block and defeat the very loop that is watching. `WaitForExit()` and `Refresh()`
do not fill it in either (verified on PowerShell 5.1). The blank is kept rather than
papered over, because a made-up code would be worse than none.

combined with the child's own stderr, which for the case above is the full Node
stack trace naming the path it could not resolve.

## `Auth fail for methods 'publickey,password'` is almost never about the key

This cost an hour on a working setup, and the mistake was reading the message as
being about the key. It is not a statement about the key at all — JSch produces it
whenever authentication ends without success, and it names the methods the *server*
offered, not the ones the client tried.

**The decisive evidence is on the far side, and it is an absence.** sshd logs
`Failed publickey for <user> … SHA256:<fp>` whenever a client offers a key that is
refused. So:

| The server's log shows | What it means |
| --- | --- |
| `Failed publickey … SHA256:<fp>` | The client offered that key and it was refused — now the key, the file or its permissions are worth looking at |
| **No `publickey` line at all**, only the disconnect | The client never offered a key. If the record's user does not exist on that machine, sshd will not read any `authorized_keys`, so no key can ever appear |
| `Disconnected from invalid user <name>` | That is the answer: the username in the device record is wrong for that machine |

Measured, on a machine where the key was in `authorized_keys` byte for byte, the
file was `600` in a `700` directory owned by the account, and the same key connected
from another host:

```
Received disconnect from <phone> … Auth fail for methods 'publickey,password' [preauth]
Disconnected from invalid user deploy <phone> [preauth]      ← the whole problem
```

The far side named a user that does not exist there at all; the account was a
different one entirely. Nothing about the key was wrong,
and no amount of checking the key would have found it. **Look for the `invalid user`
line before touching the key**, and remember that the failure message cannot tell
the two cases apart:

```
com.jcraft.jsch.JSchException: Auth fail for methods 'publickey,password'   ← refused, or never offered
com.jcraft.jsch.JSchException: USERAUTH fail                                ← a key was offered and refused
```

`USERAUTH fail` with no `for methods` suffix is the narrower message: it means a key
was actually presented. The longer one is the ambiguous case, and the server log is
the only thing that separates them.

## Diagnosing a connection

`CryptoProbe` drives the real `RemoteSession` from an adb shell and reports to
logcat. It is inert without its extras, and it is not a separate component — it
hangs off the launcher activity, because that is the only thing a shell may start
without the app exporting a way in:

```
adb shell am start -n dev.dshtabs/.MainActivity \
  --es probeHost 192.168.1.10 --es probeUser me --es probeKeyFile /data/local/tmp/id_key
adb logcat -s DshCryptoProbe:I
```

Add `--ez probeWeb true` to also load the result into a real `WebView` and report
the title, the URL after the token redirect, and the page text. That is how a blank
screen becomes a sentence, and it is what proved the last link in the chain.

### The probe is debug-only, and that is a security boundary

**It used to be reachable in a release build, and that was a real hole.** The
launcher activity has to be `exported="true"`, so *any* installed app — not just
adb, and without root — can start it with extras of its own choosing. With the
probe ungated, those extras were reachable too:

| Extra | What a hostile app got |
| --- | --- |
| `probeUseAppKey` | This app authenticates with **its real private key**, to whichever host the caller names |
| `probeCommand` | An **arbitrary command** run on that host, under an authentication the far side believes is this app |
| `probeKeyFile` | A private key read from any path the caller names |

`CryptoProbe.requested()` now starts with `BuildConfig.DEBUG`, so a release build
ignores the extras however they arrived. The code is still present in a release
APK — `minifyEnabled` is false — and that is fine: what matters is that nothing
reaches it.

**A debug build is still a debug build.** The gate removes the hole from anything
published; it cannot remove it from a debug APK installed for development, because
the probe is the thing that makes that build useful. Treat a debug install as a
development device's build, not as one to carry.

## What was verified, and how

Every one of these was measured on the Redmi itself, not inferred:

| Claim | Evidence from the device |
| --- | --- |
| SSH handshake with a real OpenSSH 9.5 server | `connect … → remote platform: windows` — Ed25519 host key, curve25519 key exchange, public-key auth |
| Host key pinning works | `HOSTKEY AAAAC3NzaC1lZDI1NTE5AAAAI<redacted>…` captured on first use |
| The remote Harness starts | `dsh web: http://127.0.0.1:60136/?token=…` streamed back over the exec channel |
| The port forward binds and carries bytes | `tunnel 127.0.0.1:39231 -> 127.0.0.1:60136`, then `FORWARD_FIRST_BYTES <<HTTP/1.1 401 Un>>` — a real response from the far side, refused for want of the token, which is the fence working |
| The token leaves the address bar | `HTTP_TOKEN status=303 location=./` then `HTTP_ROOT status=200` — the client's own half of the protocol, with a real cookie |
| The document is really the Harness | `HTTP_BODY bytes=35182 looksLikeHarness=true` |
| **WebView loads the remote Harness** | `WEB_TITLE DeepSeek Harness`, `WEB_STATE ready=complete boot=true url=http://127.0.0.1:37177/`, `WEB_TEXT <<探索未至之境 … DeepSeek-V41-Flash>>` |
| The realtime channel connects | `connection lost, retry` console lines appear the instant the session is stopped |
| Teardown reaps the far side | `STOPPED`, and the far side's `dsh web` is gone — checked with `pgrep` over a separate connection |
| The app's own key authenticates | `KEYGEN public=ssh-ed25519 AAAAC3NzaC1lZDI1NTE5<redacted>`, pasted into a real `sshd`'s `authorized_keys` with `PasswordAuthentication no`, then `credential=PrivateKey` and `KEY-AUTH-OK` from the remote |
| Multi-device | three tabs render from the book by name (`box-a`, `box-b`, `box-a (bridge)`) in a screenshot |
| **The whole thing, in use** | This README's verification round was driven from the tablet: an agent running on the PC was reached through the app's tunnel, and the conversation it produced is the record. sshd logged the tablet's own key — `SHA256:<redacted>` — from its Tailscale address, and the `dsh web` serving that GUI is a child of the PowerShell that the tablet's session started |

The protocol's own contract was verified separately against two real machines with
the desktop project's live suite: the readiness line, the token→cookie exchange, the
document load, the 401 fence, a unary RPC returning 200, and teardown leaving no
orphan.

## When the remote server ends by itself

The whole client rests on one assumption: while the SSH session is up, so is the
Harness behind it. **That assumption is false**, and this is the failure it hides. The
far side's profile is configured `patchReload: live`, so an agent that edits a plugin —
an ordinary thing to ask it to do — makes the Harness restart itself mid-turn.
Measured:

```
23:29:15  profiles/web/cordis.patch.yml written    ← the agent toggling a plugin
          the Harness exits; sshd logs no disconnect at all
          the interface sits in "connection lost, retry #50"
```

Nothing in the connection reported a problem. The tab went on claiming to be running,
the forward pointed at a dead port, and a turn that could never finish looked like a
turn that was still going.

Three things fix that, and the third is what makes the first two worth having:

1. **The remote program reports what it resolved** — which `node`, which script,
   whether it exists, and the working directory — to stderr, before it starts anything.
   Two launches that are equivalent at a prompt behave differently once PowerShell is
   spawned by sshd with redirected pipes, and when the server then fails the transcript
   otherwise says only *that* it failed:

   ```
   launch node=C:\Program Files\nodejs\node.exe
   launch script=…\@deepseek-ai\dsh\lib\bin.js exists=True
   ```

2. **A dead server is noticed.** `watchUntilEnded` polls the exec channel, which closes
   when the remote program does. JSch offers no callback for it, and the alternative is
   to wait for output that will never arrive.
3. **Then it reconnects by itself**, within a bounded budget, and says so:

   ```
   The server on build-host stopped (exit code 1).

   Starting a new one — attempt 1 of 3…
   ```

Silent recovery would be worse than the failure here. The conversation the operator was
watching has ended and they need to know that, rather than wonder why the answer never
came. Three attempts, cleared by any successful connect, so a machine that restarts
once an hour is not treated as one that keeps dying — and a server that dies on every
boot is not restarted in a loop on someone else's machine, from a phone in a pocket.

Pressing **×** cancels the watcher, so a tab you disconnected stays disconnected.

## Known gaps

- **Doze will eventually stall a session.** A foreground service keeps the process
  out of the cached state — which is what otherwise *closes the TCP sockets* — but
  Doze suspends the whole device's network and the exemption that lifts it is a
  Play-policy matter an SSH tunnel is not on the list for.
- **`specialUse` foreground service type.** It is the only type with no runtime
  prerequisite, and it is reviewed when an app is submitted to Play. For a
  sideloaded personal build that is irrelevant; for a Play release it is a question
  to answer first.
- **The port lives on `127.0.0.1` only.** Loopback is not app-isolated, so another
  app on the phone can reach the forwarded port. It is the library's forward, so
  nothing here can change it beyond what the Harness itself does — and it is the
  reason the URL carries a token at all.
- **A Windows remote launched through `Start-Process` can fail on a profile
  junction.** Seen once against the build machine itself: `dsh` could not `realpath`
  a `node_modules` junction and exited, while the identical command typed at a
  prompt booted the same profile fine. The desktop app's Windows branch runs the
  same program and is documented as working against a real Windows host, so this
  looks specific to a profile with linked plugins rather than to the branch — but it
  is written down because it cost an hour to find and would cost another.
- **The desktop client's Windows branch has converged, and the parity test is what
  found the gap.** This client resolves `node` itself, reports what it resolved, and
  watches the child for 60 seconds so a `dsh` that exits without announcing a URL
  arrives as a transcript line instead of a wait that never ends. The desktop client
  once did none of that — it handed the `.cmd` shim straight to `Start-Process` and had
  no watchdog, so the same failure there was the hang this file's own section
  documents. It has since adopted all of it, and the two Windows programs are now
  **byte-identical**; the parity test asserts equality rather than the fragment list
  it used for as long as they differed.

  What neither client could do is verify it against a real Windows remote: the
  machine that would have been the subject (`box-b`) was unreachable throughout, and
  a Windows host is the only place this branch can be exercised. The desktop side is
  therefore a faithful copy of a program this client has run for real, not an
  independent implementation — which is the most confidence available without the
  host, and the reason it was copied rather than rewritten smaller.
