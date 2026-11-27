/*
 * The bridge between xterm.js and the app.
 *
 * xterm does all the terminal work — parsing, wrapping, selection, the palette — and
 * this file only wires it to the Kotlin side: output arrives through `dshTerm.write`,
 * keyboard input and the size the fit addon computed leave through `DshTerminal`
 * (added with `addJavascriptInterface`).
 *
 * The palette matches the desktop client's terminal so that the two clients look like
 * the same tool, and the background matches `TerminalView.TERMINAL_BG`, the colour the
 * WebView is painted before the first frame.
 */
(function () {
	'use strict'

	function boot() {
		var host = document.getElementById('terminal')
		if (typeof Terminal !== 'function' || typeof FitAddon !== 'object' || typeof FitAddon.FitAddon !== 'function') {
			host.textContent = 'xterm.js could not be loaded from this build.'
			return
		}

		var term = new Terminal({
			fontFamily: 'monospace',
			fontSize: 13,
			lineHeight: 1.2,
			cursorBlink: true,
			scrollback: 5000,
			theme: {
				background: '#15161b',
				foreground: '#ececf2',
				cursor: '#6aa5ff',
				cursorAccent: '#15161b',
				selectionBackground: '#33415c',
				black: '#15161b',
				red: '#e2605e',
				green: '#4ec97a',
				yellow: '#e0a33e',
				blue: '#6aa5ff',
				magenta: '#b48ce6',
				cyan: '#4ec9c9',
				white: '#ececf2',
				brightBlack: '#71768a',
				brightRed: '#ff7b78',
				brightGreen: '#6fe39a',
				brightYellow: '#f2bb5c',
				brightBlue: '#87b8ff',
				brightMagenta: '#c9a6f2',
				brightCyan: '#7fdede',
				brightWhite: '#ffffff'
			}
		})

		var fit = new FitAddon.FitAddon()
		term.loadAddon(fit)
		term.open(host)

		// Fitting is coalesced: the resize observer can fire several times for one
		// layout (the keyboard sliding up animates), and each fit tells the pty a new
		// size, which the shell answers by redrawing its prompt.
		var fitTimer = 0
		function fitNow() {
			if (fitTimer) return
			fitTimer = setTimeout(function () {
				fitTimer = 0
				try { fit.fit() } catch (_) { /* the view is not measurable yet */ }
			}, 60)
		}

		term.onData(function (data) {
			if (window.DshTerminal) window.DshTerminal.send(data)
		})
		term.onResize(function (size) {
			if (window.DshTerminal) window.DshTerminal.resize(size.cols, size.rows)
		})

		if (window.ResizeObserver) new ResizeObserver(fitNow).observe(host)
		window.addEventListener('resize', fitNow)

		window.dshTerm = {
			write: function (text) { term.write(text) },
			focus: function () { term.focus() },
			fit: function () { fitNow() },
			selection: function () { return term.getSelection() },
			paste: function (text) { term.paste(text) }
		}

		setTimeout(function () {
			fitNow()
			term.focus()
		}, 0)
		if (window.DshTerminal) window.DshTerminal.ready()
	}

	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot)
	else boot()
})()
