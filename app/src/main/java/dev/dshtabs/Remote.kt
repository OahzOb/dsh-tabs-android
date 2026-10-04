package dev.dshtabs

/**
 * The remote half of dsh-tabs, an Android client.
 *
 * This file is a **port**, not a reimplementation, of `src/remote.js` in the
 * `dsh-tabs` desktop application. Everything here was paid for once already:
 * the readiness line, the `stdin`-EOF teardown contract, the tilde expansion
 * that single quotes would otherwise suppress, the `-EncodedCommand` quoting
 * that survives ssh joining its argv through the remote's `cmd.exe`, and the
 * platform classification that must not mistake an unreachable host for a
 * Windows one.
 *
 * The reasons travel with the code, because the failure modes are invisible
 * until a real device is on the other end.
 *
 * **It must stay byte-identical to the Node version.** `tools/parity.mjs` in the
 * parent repository runs both implementations over the same device records and
 * fails on any difference, which is the only thing that keeps two ports of the
 * same protocol honest. When the protocol changes, change both — the test exists
 * so that forgetting is loud.
 */
object Remote {
	/** The readiness line `dsh web` prints once its server is listening. */
	val READY_LINE = Regex("""dsh web:\s*(http://\S+)""")

	/** How long a remote `dsh web` may take to announce its URL. */
	const val START_TIMEOUT_MS = 45_000L

	/** How long a plain probe may take. */
	const val PROBE_TIMEOUT_MS = 20_000L

	/** How long the tunnel may take to accept a local connection. */
	const val TUNNEL_TIMEOUT_MS = 20_000L

	/** Default SSH port. */
	const val DEFAULT_SSH_PORT = 22

	/** Where the far side's `dsh` is started. */
	const val SERVER_ARGV = "dsh web --no-open --port 0"

	/**
	 * Find a readiness URL in accumulated output.
	 *
	 * Only **complete lines** are considered by default. The pattern ends in `\S+`,
	 * which will happily match a URL that a pipe split mid-write, so matching the
	 * raw buffer resolves with a truncated URL — a connect that succeeds or fails
	 * depending on how the operating system happened to chunk the output, which is
	 * the worst kind of intermittent. Reproduced on the desktop side by writing
	 * `http://127.0.0.` / `1:42341/?tok` / `en=abc\n` in three chunks, which
	 * yielded the truncated URL and a port of `NaN`.
	 *
	 * @param raw everything the command has printed so far.
	 * @param partialToo also accept an unterminated trailing line, which is only
	 *   safe once the process is known to have stopped printing.
	 * @return the URL, or null.
	 */
	fun readyUrl(raw: String, partialToo: Boolean = false): String? {
		val text = if (partialToo) raw else raw.substring(0, raw.lastIndexOf('\n') + 1)
		return READY_LINE.find(text)?.groupValues?.get(1)
	}

	/**
	 * Quote one string for a POSIX shell so it survives as a single word.
	 *
	 * A single quote cannot appear inside a single-quoted string, so each one is
	 * closed, escaped, and reopened — `'` becomes `'\''`. Every other byte is safe.
	 */
	fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

	/**
	 * Make a bare `dsh` resolvable inside a NON-interactive login shell.
	 *
	 * This is not a nicety — it is load-bearing. A node installed through nvm puts
	 * its bin directory on PATH from `~/.bashrc`, which bash sources only for
	 * INTERACTIVE shells. `ssh host 'bash -lc "dsh --version"'` therefore fails with
	 * `dsh: command not found` on an otherwise perfectly configured machine, while
	 * the same command typed by hand works.
	 *
	 * `remoteCommand()` now asks for an interactive shell, which sources `~/.bashrc`
	 * and solves this class of problem wholesale. This preamble is retained because
	 * it is free and covers the case where a machine resolves `dsh` without any rc
	 * file's help.
	 */
	fun resolvePreamble(): String = listOf(
		"command -v dsh >/dev/null 2>&1 || { [ -s \"\$HOME/.nvm/nvm.sh\" ] && . \"\$HOME/.nvm/nvm.sh\" >/dev/null 2>&1; true; }",
		"command -v dsh >/dev/null 2>&1 || { for d in \"\$HOME\"/.nvm/versions/node/*/bin \"\$HOME/.local/bin\" /usr/local/bin; do [ -x \"\$d/dsh\" ] && PATH=\"\$d:\$PATH\" && break; done; true; }"
	).joinToString("; ")

	/**
	 * Build the `cd` that enters a device's working directory.
	 *
	 * Tilde expansion is the entire reason this helper exists. `cd '~/x'` does NOT
	 * work: the single quotes that make the path injection-safe also suppress the
	 * expansion that turns `~` into `$HOME`, so the directory is looked up literally
	 * and every device configured as `~/...` dies at startup with `No such file or
	 * directory`. Splitting the tilde off and quoting only the remainder keeps both
	 * the expansion and the safety.
	 */
	fun remoteCd(directory: String): String {
		if (directory == "~") return "cd \"\$HOME\""
		if (directory.startsWith("~/") || directory.startsWith("~\\")) {
			return "cd \"\$HOME\"/" + shellSingleQuote(directory.substring(2))
		}
		return "cd " + shellSingleQuote(directory)
	}

	/**
	 * Whether a launch directory is one both clients will agree to use.
	 *
	 * **This client does not need it, and applies it anyway.** The program built here
	 * goes to `ChannelExec`, so no shell ever parses the directory — unlike the
	 * desktop app, whose Windows local tab hands `cd /d "<directory>" && dsh web …`
	 * to `cmd.exe` as a single command string, where a directory containing a double
	 * quote closes the quote and the rest becomes a new command. Nothing in this app
	 * can be reached that way.
	 *
	 * What it buys is **agreement**. `Device`'s comment calls the book format "a
	 * contract with another program", and a contract where one side accepts an entry
	 * the other refuses is a machine that works on the phone and not on the desktop.
	 * The operator cannot tell which client is wrong, and the one they try second
	 * looks broken. So the same characters are refused here, for the same reason the
	 * desktop refuses them: a path that needs `%` or `!` in it is not a path this
	 * pair of clients can be trusted to pass to two different shells.
	 *
	 * @param directory the configured directory, or null when there is none.
	 * @return the reason it cannot be used, or null when it can.
	 */
	fun directoryProblem(directory: String?): String? {
		if (directory.isNullOrEmpty()) return null
		val offending = directory.toSet().filter { it in "\"'%!&|<>^" }.sorted()
		if (offending.isEmpty()) return null
		val shown = offending.joinToString(", ") { "`$it`" }
		return "the launch directory contains $shown, which cannot be passed to a shell safely"
	}

	/**
	 * Build the remote POSIX shell program that starts the far side's Harness.
	 *
	 * The `cat > /dev/null` is the teardown contract, and it is deliberate. A remote
	 * command started over ssh does NOT reliably die when the connection goes away:
	 * measured on the desktop project, a plain `exec dsh web` and even one under
	 * `ssh -tt` both survived the client being killed outright, leaving an orphaned
	 * server holding the remote port. Blocking on stdin is deterministic instead —
	 * sshd hands the remote command a pipe, and that pipe reaches EOF the moment the
	 * connection ends however it ends, including a hard kill, so the backgrounded
	 * server is always reaped.
	 *
	 * **This is why the caller MUST keep stdin open** (`cat` blocks on it) and close
	 * it as the first step of teardown. Closing stdin earlier would kill the server
	 * the instant it started.
	 *
	 * `--port 0` is deliberate: the remote's OS picks a free port, so a connect can
	 * never collide with a server the operator started by hand, with a stale
	 * instance, or with another device entry aimed at the same host. The real port
	 * is read back from the readiness line, and the tunnel is opened to it
	 * afterwards — which is why a device needs two channels rather than one.
	 */
	fun posixProgram(directory: String?): String {
		val enter = if (directory.isNullOrEmpty()) "" else remoteCd(directory) + " || exit 1; "
		return "${resolvePreamble()}; $enter$SERVER_ARGV & child=\$!; cat > /dev/null; kill \$child 2>/dev/null; wait \$child 2>/dev/null; true"
	}

	/**
	 * Quote one value as a PowerShell single-quoted literal.
	 *
	 * PowerShell escapes a quote inside a single-quoted string by doubling it.
	 */
	fun windowsLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

	/**
	 * The PowerShell prologue that locates the far side's `dsh`.
	 *
	 * There is no nvm and no `~/.local/bin` on Windows: a global npm install lands a
	 * `.cmd` shim in `%APPDATA%\npm`, which is on PATH only if the installer put it
	 * there. The explicit paths are the fallback for a PATH that was trimmed, which
	 * is common for a non-interactive remote session.
	 *
	 * **`$dsh` is the shim, and `$node` is the interpreter that runs it.** That pair
	 * replaces the shim alone, and the reason is measured rather than tidy: launching
	 * the `.cmd` directly through `Start-Process` makes `dsh` fail with
	 *
	 * ```
	 * Error: UNKNOWN: unknown error, realpath
	 *   'C:\\Users\\…\\.dsh\\profiles\\web\\node_modules\\dsh-turn-restart'
	 * ```
	 *
	 * on a machine where the identical command at a prompt boots the same profile
	 * fine. A `.cmd` has to be run by `cmd.exe`, and the extra process layer is what
	 * changes how the junction is resolved. Starting `node.exe` on the shim's target
	 * script removes that layer, and with it the failure — verified by launching both
	 * ways against the same broken profile.
	 *
	 * `$shimDir` exists because the `.cmd` hardcodes `%~dp0` for both the interpreter
	 * and the script, so the same two names are derived from the shim's own location
	 * rather than assumed to be on PATH.
	 */
	fun windowsResolve(): String = listOf(
		"\$dsh = \$null",
		"\$c = Get-Command dsh -CommandType Application -ErrorAction SilentlyContinue",
		"if (\$null -ne \$c) { \$dsh = @(\$c)[0].Source }",
		"if ([string]::IsNullOrEmpty(\$dsh)) { foreach (\$p in @(\"\$env:APPDATA\\npm\\dsh.cmd\", \"\$env:LOCALAPPDATA\\pnpm\\dsh.cmd\", \"\$env:ProgramFiles\\nodejs\\dsh.cmd\")) { if (Test-Path -LiteralPath \$p) { \$dsh = \$p; break } } }",
		"if ([string]::IsNullOrEmpty(\$dsh)) { [Console]::Error.WriteLine('dsh is not on PATH on this Windows host'); exit 127 }"
	).joinToString("; ")

	/**
	 * Build the remote PowerShell program that starts a Windows host's Harness.
	 *
	 * It reproduces the POSIX branch's contract rather than inventing a new one,
	 * because the contract is what keeps a disconnected session from leaving an
	 * orphaned server holding the remote port:
	 *
	 * - the server is a child process whose stdout is **inherited**, not redirected
	 *   through a file, so the readiness line streams straight down the ssh channel
	 *   with no temp file and no sharing problem;
	 * - `[Console]::In.ReadToEnd()` blocks until stdin reaches EOF, which sshd
	 *   delivers the moment the connection ends however it ends — the same
	 *   deterministic teardown the POSIX branch gets from `cat > /dev/null`.
	 *
	 * The teardown kills the whole **tree**, not just the process it started, and
	 * that is not belt-and-braces: an npm global install resolves to a `.cmd` shim,
	 * so the started process is `cmd.exe` and the Harness is its `node` child.
	 * `Stop-Process` on the shim left `node` running.
	 *
	 * `~` is a POSIX habit, so it is translated here instead of being handed to
	 * `Set-Location`, which would treat it as a relative path.
	 *
	 * Verified against a real Windows host on the desktop side.
	 *
	 * **The statements are in one list, in the order they run, and that order is part
	 * of a contract with the desktop client.** `RemoteParityTest` compares this
	 * program against `dsh-tabs/docs/contract.json` byte for byte, so the shape here
	 * is not a style choice: an earlier version split the launch into a separate
	 * `windowsLaunch()` helper and put `windowsResolve()` first, which produced the
	 * same statements in an order the comparison rejects.
	 */
	fun windowsProgram(directory: String?): String {
		val dir = directory ?: ""
		val parts = mutableListOf(windowsResolve())
		parts += "\$node = 'FALLBACK'"
		parts += "\$shimDir = Split-Path -Parent \$dsh"
		parts += "\$binJs = Join-Path \$shimDir 'node_modules\\@deepseek-ai\\dsh\\lib\\bin.js'"
		// A missing `bin.js` used to fall back to `$dsh` itself, and that cannot work
		// here: the launcher always starts `$node`, so no script would be named and
		// `node web --no-open --port 0` would exit on `Cannot find module …\web`,
		// blaming the wrong thing. Refused by name instead. Reachable wherever the
		// shim's own directory carries no `node_modules\@deepseek-ai\dsh` — a pnpm
		// global install, or a `dsh` shimmed in from somewhere else, among them.
		parts += "if (-not (Test-Path -LiteralPath \$binJs)) { [Console]::Error.WriteLine(\"dsh is at \$dsh, but \$binJs does not exist; this launcher starts node on bin.js rather than the .cmd shim, so bin.js has to sit in the node_modules beside the shim\"); exit 127 }"
		// The interpreter is looked for in the shim's own directory first, because an npm
		// global install puts `node.exe` there; `%ProgramFiles%\nodejs` is the ordinary
		// install, and the bare name is the last resort. Each candidate is tested before
		// it is chosen, so the log below can say which one actually won.
		parts += "\$cand = @((Join-Path \$shimDir 'node.exe'), (Join-Path \$env:ProgramFiles 'nodejs\\node.exe'), 'node')"
		parts += "foreach (\$n in \$cand) { if (\$node -eq 'FALLBACK') { try { if (Get-Command \$n -ErrorAction Stop) { \$node = \$n } } catch { } } }"
		if (dir.isNotEmpty()) {
			// **One statement, and it has to stay one.** The statements here are joined
			// with `"; "`, so writing the tilde translation as three parts puts a
			// semicolon between the closing brace and `elseif` — and PowerShell then
			// reads `elseif` as a *command name*:
			//
			//   elseif : The term 'elseif' is not recognized as the name of a cmdlet,
			//   function, script file, or operable program.
			//
			// Measured against box-b, from the far side's own stderr, where it meant a
			// machine configured `~/...` never had its `~` translated. The chain below is
			// still three statements; it is the *joins between them* that had to change,
			// so the `if`/`elseif` pair is never split across one.
			parts += "\$dir = ${windowsLiteral(dir)}; if (\$dir -eq '~') { \$dir = \$env:USERPROFILE } elseif (\$dir.StartsWith('~/') -or \$dir.StartsWith('~\\')) { \$dir = Join-Path \$env:USERPROFILE \$dir.Substring(2) }; \$null = 0"
			parts += "Set-Location -LiteralPath \$dir -ErrorAction Stop"
		}
		// The script is named first and always: `$binJs` is the only thing this
		// launcher starts, and the check above guarantees it exists.
		parts += "\$argv = @(\$binJs)"
		parts += "\$argv += @('web','--no-open','--port','0')"
		// Write the resolution to stderr before starting anything, so it cannot be
		// mistaken for the readiness line, which the client reads from stdout. Naming
		// the executable, the script and the directory is what turns "it failed" into
		// an answer.
		parts += "[Console]::Error.WriteLine(\"launch node=\$node\")"
		parts += "[Console]::Error.WriteLine(\"launch script=\$binJs exists=\$(Test-Path -LiteralPath \$binJs)\")"
		parts += "[Console]::Error.WriteLine(\"launch cwd=\$((Get-Location).Path)\")"
		parts += "\$proc = Start-Process -FilePath \$node -ArgumentList \$argv -NoNewWindow -PassThru"
		parts += "\$sw = [Diagnostics.Stopwatch]::StartNew()"
		// A server that could not start must not become silence. Without this the program
		// sits in `ReadToEnd()` for ever while the client waits for a readiness line that
		// will never come, and the operator sees a connection that never finishes and
		// never fails — which is the worst of both. Measured: `Start-Process` on this
		// machine's `dsh.cmd` shim left no process behind and printed nothing at all.
		//
		// Sixty seconds is the window; `dsh web` normally announces itself in about two.
		//
		// The message prints an **empty exit code**, which reads like a bug in the
		// message rather than a fact: `Start-Process -PassThru` leaves `$proc.ExitCode`
		// unset unless it is also given `-Wait`, and `-Wait` would block and defeat the
		// loop. Verified on PowerShell 5.1 — `WaitForExit()` and `Refresh()` do not fill
		// it in either. The blank is kept rather than papered over.
		parts += "while (\$sw.Elapsed.TotalSeconds -lt 60) { if (\$proc.HasExited) { [Console]::Error.WriteLine(\"dsh exited with code \$(\$proc.ExitCode) before announcing a URL\"); break }; Start-Sleep -Milliseconds 500 }"
		parts += "try { [Console]::In.ReadToEnd() | Out-Null } finally { if (\$null -ne \$proc -and -not \$proc.HasExited) { taskkill /PID \$proc.Id /T /F 2>&1 | Out-Null } }"
		return parts.joinToString("; ")
	}

	/**
	 * The readable part of a remote's stderr, for a failure message.
	 *
	 * PowerShell serialises its error stream as **CLIXML** whenever stderr is
	 * redirected, so a Windows remote that fails sends one large `<Objs …>` document
	 * rather than the sentences inside it. Measured against a real Windows host: 1131
	 * bytes across two lines, of which the only useful part was
	 *
	 *     Set-Location : Cannot find path 'C:\…' because it does not exist.
	 *
	 * — buried in an `<S S="Error">` record with its newlines escaped as `_x000D_` and
	 * `_x000A_`. The **first** error record is the one that names the fault; the rest
	 * is the source excerpt and `CategoryInfo`, which is for whoever edits the script
	 * rather than whoever is trying to connect.
	 *
	 * @param text everything the stream carried.
	 * @return one line, or an empty string when there is nothing to say.
	 */
	fun readableStderr(text: String?): String {
		val raw = text ?: return ""
		val records = Regex("""<S S="Error">([\s\S]*?)</S>""").findAll(raw)
			.map { it.groupValues[1].replace("_x000D_", " ").replace("_x000A_", " ").trim() }
			.toList()
		// No CLIXML: an ordinary shell's stderr is already what a reader wants, and it
		// is kept whole because there is no such structure to trim.
		val chosen = records.firstOrNull() ?: raw.replace(Regex("""#<\s*CLIXML"""), "")
		val collapsed = chosen.replace(Regex("""\s+"""), " ").trim()
		// Enough to name the problem, short enough not to become the whole panel.
		return if (collapsed.length > 300) collapsed.take(300) + "…" else collapsed
	}

	/**
	 * Wrap one PowerShell script as a single ssh command argument.
	 *
	 * `-EncodedCommand` takes base64 UTF-16LE, which is the only quoting strategy
	 * that survives the three layers between here and the script: ssh joins its
	 * argv, the remote runs it through `cmd.exe`, and the script itself is full of
	 * characters cmd would otherwise eat. The encoding contains no spaces or quotes,
	 * so every layer passes it through untouched.
	 *
	 * This is the one function whose port is not a straight transliteration: the
	 * Node version calls `Buffer.from(script, 'utf16le').toString('base64')`, and
	 * here it is UTF-16LE bytes through the platform Base64 encoder. `tools/parity.mjs`
	 * checks the two agree, including on non-ASCII input.
	 */
	fun windowsCommand(script: String): String =
		"powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
			java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

	/**
	 * Wrap one shell program as a single remote command argument.
	 *
	 * On the desktop this is handed to ssh, which joins its remaining argv with
	 * spaces, so passing exactly one element hands the remote shell the program
	 * verbatim with no further quoting. Here the same string is handed to
	 * `Session.exec` through a library that preserves it as one argv element, so the
	 * property is kept — but it is a property of the *caller*, not of this function,
	 * and a caller that splits arguments would break the POSIX branch silently.
	 *
	 * `bash -lic` is load-bearing, not cosmetic. bash sources `~/.bashrc` ONLY for
	 * interactive shells, and that file is where people put the exports their tools
	 * need. Launching the far side's Harness with a non-interactive shell silently
	 * strips all of them: measured on a real device, `DEEPSEEK_API_KEY` was set in
	 * `~/.bashrc`, empty under `bash -lc`, and the remote Harness therefore started
	 * with no credential and asked the operator to type one in — for a machine that
	 * already had it. PATH has the same failure mode.
	 *
	 * The cost is bash's two job-control complaints on stderr when it is interactive
	 * without a controlling terminal. They are harmless, they land in the connect
	 * transcript, and they are the only visible difference.
	 */
	fun remoteCommand(program: String, platform: Platform = Platform.POSIX): String =
		if (platform == Platform.WINDOWS) windowsCommand(program) else "bash -lic " + shellSingleQuote(program)

	/**
	 * Pick the remote program for a host's shell family.
	 */
	fun remoteProgram(directory: String?, platform: Platform = Platform.POSIX): String =
		if (platform == Platform.WINDOWS) windowsProgram(directory) else posixProgram(directory)

	/**
	 * Decide a host's shell family from a `uname -s` probe.
	 *
	 * `uname -s` is one argv element with no metacharacters, so it survives every
	 * shell: a POSIX host answers `Linux` or `Darwin` on stdout, while both cmd.exe
	 * and PowerShell on Windows fail the command and put nothing on stdout.
	 *
	 * @param exitCode the ssh command's exit code.
	 * @param stdout the probe's stdout.
	 * @return the platform, or a connection error when ssh itself failed.
	 */
	fun classifyPlatform(exitCode: Int, stdout: String): PlatformResult {
		// 255 is ssh's own failure code. It means the connection failed, not that the
		// far side is Windows — an unreachable host has to fail once, with the ssh
		// error, instead of twice under a platform guess.
		if (exitCode == 255) return PlatformResult(error = true)
		val answer = stdout.trim().lowercase()
		val posix = listOf("linux", "darwin", "freebsd", "openbsd", "netbsd", "sunos", "aix")
		return PlatformResult(platform = if (posix.any { answer.startsWith(it) }) Platform.POSIX else Platform.WINDOWS)
	}

	/**
	 * Split a readiness URL into the parts a tunnel needs.
	 *
	 * Parsed by hand rather than through `java.net.URI`, because the token is
	 * base64url and the port must survive exactly. The authority is always
	 * `127.0.0.1:<port>`, which is what the far side binds.
	 *
	 * @param url the URL from the readiness line.
	 * @return the remote port and the session token, either of which may be absent.
	 */
	fun parseReadyUrl(url: String): ReadyUrl {
		val afterScheme = url.substringAfter("://", url)
		val authority = afterScheme.substringBefore('/').substringBefore('?')
		val port = authority.substringAfter(':', "").toIntOrNull() ?: 0
		val query = afterScheme.substringAfter('?', "")
		val token = query.split('&')
			.map { it.split('=', limit = 2) }
			.firstOrNull { it.size == 2 && it[0] == "token" }
			?.get(1)
		return ReadyUrl(port = port, token = token)
	}

	/**
	 * The URL a local client should open for a forwarded port.
	 *
	 * The token is a one-time entry ticket that the server exchanges for a signed
	 * cookie and a relative redirect to `./`, so opening this URL is all a browser
	 * has to do — measured against a real device: the address bar ends up on `/`,
	 * with no token in it, and every later reload uses the cookie.
	 */
	fun localUrl(localPort: Int, token: String?): String =
		"http://127.0.0.1:$localPort/" + (if (token == null) "" else "?token=$token")

	/** Which shell family the far side speaks. */
	enum class Platform { POSIX, WINDOWS }

	/** The outcome of classifying a host. */
	data class PlatformResult(val platform: Platform? = null, val error: Boolean = false)

	/** The parts of a readiness URL. */
	data class ReadyUrl(val port: Int, val token: String?)
}
