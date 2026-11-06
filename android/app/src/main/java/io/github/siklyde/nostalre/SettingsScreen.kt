package io.github.siklyde.nostalre

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.siklyde.nostalre.ServerSettings.Setting
import io.github.siklyde.nostalre.Ui.fit
import io.github.siklyde.nostalre.Ui.pad
import kotlin.concurrent.thread

/**
 * The hub's settings, a screen like character creation's, in tabs: the server's ([ServerSettings.categories]: rates,
 * monsters, AI players, character, loot, skills, NPCs), then the app's own (GM commands, [Account]; the English text,
 * [EnglishText]; the logs and the graphics trace, [Diagnostics]). Server settings save when the screen closes, [onClose]
 * told to restart the servers (with them, or with new English text, which grf-server reads at its start); GM commands
 * and the trace save at once, the English text installs and the logs copy on their own threads.
 *
 * Controller: L1/R1 change tab, the D-pad goes up and down the rows and left and right changes the one it is on, A sets
 * it, Y puts the tab's settings back to the official ones, X leaves without saving, B or Start saves and goes back
 * ([Hub]). Touch: tap.
 */
class SettingsScreen(
	private val context: Context,
	isGm: Boolean,
	private val onClose: ( restart: Boolean ) -> Unit,
) {
	private val saved = ServerSettings.load( context )
	private var draft = saved
	private var gm = isGm
	private var trace = Diagnostics.tracing( context )
	private val gameDir = GameDir.get( context )
	private val englishBefore = EnglishText.finished
	private var stopListening: () -> Unit = {}

	private val serverChanged: Boolean get() = draft != saved
	private val englishChanged: Boolean get() = EnglishText.finished != englishBefore

	/** A tab: a server category ([restarts]: saved on leaving, the server restarting with it) or one of the app's. */
	private class Tab( val title: String, val about: String, val settings: List<Setting<*>>, val restarts: Boolean ) {
		lateinit var label: TextView
		lateinit var bar: View
		lateinit var header: View
		lateinit var page: View
		var rows: List<Row> = emptyList()
	}

	private val tabs = ServerSettings.categories.map { Tab( it.title, it.about, it.settings, true ) } + listOf(
		Tab( "Account", "Your account on the app's server.", emptyList(), false ),
		Tab( "Game Text", "The language of the game's own text.", emptyList(), false ),
		Tab( "Diagnostics", "For a bug report: the game's logs, and more in them.", emptyList(), false ),
	)
	private var current = 0

	private val help = Ui.text( context, 13f ).fit( 10, 13 )
	private val back = Ui.hint( context, listOf( "B" ), "Back" ) { close() }.apply { isFocusable = true }
	private val backLabel = Ui.hintLabel( back )
	private val reset = Ui.hint( context, listOf( "Y" ), "Reset tab" ) { resetTab() }
	private val discard = Ui.hint( context, listOf( "X" ), "Discard" ) { discard() }
	private val account = Ui.text( context, 12f, Ui.DIM ).apply { text = Account.name( context ).orEmpty() }
	private val strip = LinearLayout( context ).apply { gravity = Gravity.CENTER_VERTICAL }
	private val stripScroll = HorizontalScrollView( context ).apply {
		isHorizontalScrollBarEnabled = false
		addView( strip )
	}
	private val pages = FrameLayout( context )
	private val refreshers = mutableListOf<() -> Unit>()

	fun view(): View {
		for( ( i, tab ) in tabs.withIndex() ) {
			if( i > 0 && tabs[i - 1].restarts && !tab.restarts )
				strip.addView( View( context ).apply { setBackgroundColor( 0x40D2EAFC ) },
					LinearLayout.LayoutParams( Ui.dp( context, 1f ), Ui.dp( context, 18f ) ).apply { marginStart = Ui.dp( context, 8f ); marginEnd = Ui.dp( context, 8f ) } )
			strip.addView( tabHeader( tab, i ) )
			tab.rows = when {
				tab.restarts -> tab.settings.map { settingRow( it ) }
				tab.title == "Account" -> listOf( toggleRow( "GM commands", "@ commands in chat, like @warp and @item. Saved now; from your next game.",
					{ gm } ) { setGm( it ) } )
				tab.title == "Game Text" -> listOf( englishRow() )
				else -> listOf( saveLogsRow(), toggleRow( "Graphics trace", TRACE_HELP, { trace } ) { setTrace( it ) } )
			}
			wire( tab.rows )
			tab.page = page( tab.rows, if( tab.restarts ) RESTART_NOTE else null )
			pages.addView( tab.page, FrameLayout.LayoutParams( -1, -1 ) )
		}
		val tabBar = LinearLayout( context ).apply {
			gravity = Gravity.CENTER_VERTICAL
			addView( Ui.glyph( context, "L1" ), LinearLayout.LayoutParams( -2, -2 ).apply { marginEnd = Ui.dp( context, 6f ) } )
			addView( stripScroll, LinearLayout.LayoutParams( 0, -2, 1f ) )
			addView( Ui.glyph( context, "R1" ), LinearLayout.LayoutParams( -2, -2 ).apply { marginStart = Ui.dp( context, 6f ) } )
		}
		val body = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			addView( tabBar, LinearLayout.LayoutParams( -1, -2 ).apply { bottomMargin = Ui.dp( context, 6f ) } )
			addView( pages, LinearLayout.LayoutParams( -1, 0, 1f ) )
		}
		select( 0, focus = false )
		return Ui.screen( context,
			Ui.breadcrumb( context, "CHARACTERS ›", "SETTINGS" ),
			account,
			body,
			help,
			listOf( Ui.hint( context, listOf( "✥" ), "choose" ), reset, discard, back ),
		)
	}

	/** The first row of the tab, so the controller starts on the screen (as character creation starts on the name). */
	fun focusFirst() = select( current )

	/** The screen's own buttons (the rows take the D-pad and A); false for the rest. */
	fun onKey( event: KeyEvent ): Boolean {
		val act: () -> Unit = when( event.keyCode ) {
			KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { { close() } }
			KeyEvent.KEYCODE_BUTTON_X -> { { if( serverChanged ) discard() } }
			KeyEvent.KEYCODE_BUTTON_Y -> { { resetTab() } }
			KeyEvent.KEYCODE_BUTTON_L1 -> { { select( ( current - 1 ).mod( tabs.size ) ) } }
			KeyEvent.KEYCODE_BUTTON_R1 -> { { select( ( current + 1 ).mod( tabs.size ) ) } }
			else -> return false
		}
		// on the press: the release of the Start press that opened the screen must not close it again
		if( event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 )
			act()
		return true
	}

	/** B, Back or Start: saves what changed and goes back. */
	fun close() {
		stopListening()
		if( serverChanged )
			ServerSettings.save( context, draft )
		onClose( serverChanged || englishChanged )
	}

	/** X: back without the server settings changed here (GM commands and the English text are in already). */
	private fun discard() {
		stopListening()
		onClose( englishChanged )
	}

	/** Y: the tab's server settings back to the official ones. */
	private fun resetTab() {
		val tab = tabs[current]
		if( !tab.restarts || draft.atDefaults( tab.settings ) )
			return
		draft = draft.reset( tab.settings )
		refresh()
	}

	private fun select( index: Int, focus: Boolean = true ) {
		current = index
		tabs.forEachIndexed { i, tab -> tab.page.visibility = if( i == index ) View.VISIBLE else View.GONE }
		refresh()
		val header = tabs[index].header
		stripScroll.post { stripScroll.smoothScrollTo( header.left - ( stripScroll.width - header.width ) / 2, 0 ) }
		if( focus )
			tabs[index].rows.firstOrNull()?.requestFocus()
		help.text = tabs[index].about
	}

	private fun refresh() {
		refreshers.forEach { it() }
		val tab = tabs[current]
		reset.visibility = if( tab.restarts && !draft.atDefaults( tab.settings ) ) View.VISIBLE else View.GONE
		discard.visibility = if( serverChanged ) View.VISIBLE else View.GONE
		backLabel.text = if( serverChanged || englishChanged ) "Save and restart server" else "Back"
		for( ( i, t ) in tabs.withIndex() ) {
			val chosen = i == current
			t.label.text = SpannableStringBuilder( t.title.uppercase() ).apply {
				if( t.restarts && draft.differs( saved, t.settings ) )
					append( " •", ForegroundColorSpan( Ui.ACCENT ), 0 )
			}
			t.label.setTextColor( if( chosen ) Ui.PARCHMENT else Ui.DIM )
			t.label.typeface = if( chosen ) Ui.bold( context ) else Ui.regular( context )
			t.bar.visibility = if( chosen ) View.VISIBLE else View.INVISIBLE
		}
	}

	/** A tab's name over the bar that marks the open one; a tap opens it (the controller has L1/R1). */
	private fun tabHeader( tab: Tab, index: Int ) = LinearLayout( context ).apply {
		orientation = LinearLayout.VERTICAL
		gravity = Gravity.CENTER_HORIZONTAL
		isClickable = true
		background = Ui.focusable( context, 0, 8f )
		pad( context, 10f, 6f )
		tab.label = Ui.text( context, 13f, Ui.DIM ).apply { letterSpacing = 0.15f }
		tab.bar = View( context ).apply { background = Ui.rounded( context, Ui.ACCENT, 1f ) }
		addView( tab.label )
		addView( tab.bar, LinearLayout.LayoutParams( -1, Ui.dp( context, 2f ) ).apply { topMargin = Ui.dp( context, 4f ) } )
		setOnClickListener { select( index ) }
		tab.header = this
	}

	/** A tab's rows in two columns (one when they fit), then [note]. */
	private fun page( rows: List<Row>, note: String? ): View {
		val split = if( rows.size > 4 ) ( rows.size + 1 ) / 2 else rows.size
		fun column( part: List<View> ) = LinearLayout( context ).apply {
			orientation = LinearLayout.VERTICAL
			part.forEach { addView( it ) }
		}
		return ScrollView( context ).apply {
			isVerticalScrollBarEnabled = false
			addView( LinearLayout( context ).apply {
				orientation = LinearLayout.VERTICAL
				addView( LinearLayout( context ).apply {
					addView( column( rows.take( split ) ), LinearLayout.LayoutParams( 0, -2, 1f ) )
					addView( column( rows.drop( split ) ), LinearLayout.LayoutParams( 0, -2, 1f ).apply { marginStart = Ui.dp( context, 28f ) } )
				} )
				if( note != null )
					addView( Ui.text( context, 12f, Ui.DIM ).apply {
						text = note
						setPadding( Ui.dp( context, 10f ), Ui.dp( context, 8f ), 0, 0 )
					} )
			} )
		}
	}

	/**
	 * A line of a page the controller rests on: [label], then its control, with [below] under them. The D-pad's left and
	 * right go to [onSide] (-1, 1), A to [onPress].
	 */
	private inner class Row( label: String, val about: String, below: View? = null ) : LinearLayout( context ) {
		var onSide: ( Int ) -> Unit = {}
		var onPress: () -> Unit = {}
		val line = LinearLayout( context ).apply {
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = Ui.dp( context, 44f )
			addView( Ui.text( context, 15f ).apply { text = label }, LayoutParams( 0, -2, 1f ) )
		}

		init {
			orientation = VERTICAL
			isFocusable = true
			background = Ui.row( context )
			setPadding( Ui.dp( context, 10f ), 0, Ui.dp( context, 6f ), if( below != null ) Ui.dp( context, 6f ) else 0 )
			addView( line )
			if( below != null )
				addView( below )
			setOnFocusChangeListener { _, focused -> if( focused ) help.text = about }
		}
	}

	/** Up and down go through [rows] in order, across the columns; left, right and A to the row. */
	private fun wire( rows: List<Row> ) = rows.forEachIndexed { i, row ->
		row.setOnKeyListener { _, code, event ->
			val down = event.action == KeyEvent.ACTION_DOWN
			when( code ) {
				KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
					if( down )
						rows.getOrNull( i + if( code == KeyEvent.KEYCODE_DPAD_UP ) -1 else 1 )?.requestFocus()
					true
				}
				KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
					// held: keeps stepping
					if( down )
						row.onSide( if( code == KeyEvent.KEYCODE_DPAD_LEFT ) -1 else 1 )
					true
				}
				KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
					if( down && event.repeatCount == 0 )
						row.onPress()
					true
				}
				else -> false
			}
		}
	}

	private fun settingRow( setting: Setting<*> ): Row = when( setting ) {
		is ServerSettings.Step -> stepRow( setting.label, setting.help, setting.steps, setting.default, setting.format,
			{ draft[setting] } ) { draft = draft.with( setting, it ) }
		is ServerSettings.Toggle -> toggleRow( setting.label, setting.help, { draft[setting] } ) { draft = draft.with( setting, it ) }
	}

	/** A choice of [steps]: its name, then ‹ the value as [show]s it ›, highlighted off the [usual] one. */
	private fun stepRow(
		label: String, about: String, steps: List<Int>, usual: Int, show: ( Int ) -> String,
		get: () -> Int, set: ( Int ) -> Unit,
	): Row {
		val row = Row( label, about )
		val value = Ui.text( context, 17f, typeface = Ui.bold( context ) ).apply { gravity = Gravity.CENTER }
		fun step( by: Int ) {
			val at = ( steps.indexOf( get() ).coerceAtLeast( 0 ) + by ).coerceIn( steps.indices )
			set( steps[at] )
			refresh()
		}
		val down = arrow( "‹", about ) { step( -1 ) }
		val up = arrow( "›", about ) { step( 1 ) }
		row.onSide = { step( it ) }
		refreshers += {
			val v = get()
			value.text = show( v )
			value.setTextColor( if( v == usual ) Ui.PARCHMENT else Ui.ACCENT )
			down.alpha = if( v == steps.first() ) 0.35f else 1f
			up.alpha = if( v == steps.last() ) 0.35f else 1f
		}
		row.line.addView( down, square() )
		row.line.addView( value, LinearLayout.LayoutParams( Ui.dp( context, 64f ), -2 ) )
		row.line.addView( up, square() )
		return row
	}

	/** An on/off choice: its name, then the two pills, the chosen one banded; left picks On, right Off, A flips. */
	private fun toggleRow( label: String, about: String, get: () -> Boolean, set: ( Boolean ) -> Unit ): Row {
		val row = Row( label, about )
		fun pick( value: Boolean ) {
			if( get() != value ) {
				set( value )
				refresh()
			}
		}
		val pills = listOf( true to "On", false to "Off" ).map { ( value, text ) ->
			value to Ui.text( context, 14f ).apply {
				this.text = text
				gravity = Gravity.CENTER
				isClickable = true
				pad( context, 16f, 0f )
				setOnClickListener {
					help.text = about
					pick( value )
				}
			}
		}
		row.onSide = { pick( it < 0 ) }
		row.onPress = { pick( !get() ) }
		refreshers += { pills.forEach { ( value, pill ) -> pill.background = Ui.choice( context, value == get() ) } }
		pills.forEach { ( _, pill ) ->
			row.line.addView( pill, LinearLayout.LayoutParams( Ui.dp( context, 64f ), Ui.dp( context, 34f ) ).apply { marginStart = Ui.dp( context, 4f ) } )
		}
		return row
	}

	/** GM commands go to the account at once; not saved, the choice shows what the account still has. */
	private fun setGm( on: Boolean ) {
		gm = on
		thread( name = "gm" ) {
			if( runCatching { Account.setGm( context, on ) }.isFailure )
				help.post {
					gm = !on
					refresh()
					help.text = "GM commands were not saved: the save database is busy. Try again."
				}
		}
	}

	/** The graphics trace goes to env.txt at once, for the next game. */
	private fun setTrace( on: Boolean ) {
		trace = on
		if( runCatching { Diagnostics.setTracing( context, on ) }.isFailure ) {
			trace = !on
			refresh()
			help.text = "The graphics trace was not saved: env.txt could not be written."
		}
	}

	/** Save logs: copies them to Download on its own thread; below, where they went or why they did not. */
	private fun saveLogsRow(): Row {
		val status = Ui.text( context, 12f, Ui.DIM ).apply { text = "Nothing saved yet." }
		val row = Row( "Save logs", SAVE_LOGS_HELP, status )
		var saving = false
		val action = Ui.text( context, 14f ).apply {
			text = "Save"
			gravity = Gravity.CENTER
			isClickable = true
			background = Ui.choice( context, true )
			pad( context, 16f, 0f )
		}
		fun save() {
			if( saving )
				return
			saving = true
			action.alpha = 0.35f
			status.text = "Saving…"
			thread( name = "save-logs" ) {
				val result = runCatching { Diagnostics.saveLogs( context ) }
				status.post {
					saving = false
					action.alpha = 1f
					status.text = result.fold( { saved ->
						val where = saved.folder.path.substringAfter( "/storage/emulated/0/" )
						val client = saved.clientLog?.let { " The game's log: ${it / 1024} KB." } ?: " No game log yet: play a game first."
						"Saved to $where: ${saved.names.joinToString( ", " )}.$client"
					}, { "Not saved: ${it.message ?: it.javaClass.simpleName}." } )
				}
			}
		}
		action.setOnClickListener {
			help.text = SAVE_LOGS_HELP
			save()
		}
		row.onPress = { save() }
		row.line.addView( action, LinearLayout.LayoutParams( Ui.dp( context, 112f ), Ui.dp( context, 34f ) ) )
		return row
	}

	/** The translation's newest commit, as GitHub gave it when the screen opened (or as an install just took it). */
	private sealed class Latest {
		object Checking : Latest()
		data class Known( val commit: String ) : Latest()
		data class Unknown( val reason: String ) : Latest()
	}

	/**
	 * English text: Download when there is none from the app, else Update, greyed until GitHub has a newer commit of
	 * the translation than the one installed; below it where it stands (the run's progress, what went wrong, what is
	 * installed and whether a newer one is out).
	 */
	private fun englishRow(): Row {
		val status = Ui.text( context, 12f, Ui.DIM )
		val row = Row( "English text", ENGLISH_HELP, status )
		var latest: Latest = Latest.Checking
		var enabled = false
		var last: EnglishText.State = EnglishText.State.Idle
		fun start() {
			if( enabled )
				gameDir?.let { EnglishText.start( context, it ) }
		}
		val action = Ui.text( context, 14f ).apply {
			gravity = Gravity.CENTER
			isClickable = true
			pad( context, 16f, 0f )
			setOnClickListener {
				help.text = ENGLISH_HELP
				start()
			}
		}
		row.onPress = { start() }
		fun show( state: EnglishText.State ) {
			val installed = gameDir?.let { EnglishText.installed( it ) } ?: EnglishText.Installed.None
			val app = installed as? EnglishText.Installed.App
			val newer = ( latest as? Latest.Known )?.commit?.takeIf { app != null && it != app.commit }
			val working = state is EnglishText.State.Working
			enabled = gameDir != null && !working && ( app == null || newer != null )
			action.text = when {
				working -> "…"
				app != null -> "Update"
				else -> "Download"
			}
			action.background = Ui.choice( context, enabled )
			action.alpha = if( enabled ) 1f else 0.35f
			val failed = ( state as? EnglishText.State.Failed )?.let { if( app == null ) "Not installed: ${it.message}." else "Not updated: ${it.message}." }
			val standing = when( installed ) {
				is EnglishText.Installed.App -> {
					val version = "Installed: ROenglishRE ${installed.commit.take( 7 )}"
					when( val known = latest ) {
						Latest.Checking -> "$version. Checking for a newer translation…"
						is Latest.Known -> if( newer != null ) "$version. A newer translation is out (${newer.take( 7 )})." else "$version, the newest translation."
						is Latest.Unknown -> "$version. Couldn't check for a newer one: ${known.reason}."
					}
				}
				EnglishText.Installed.Other -> "Set up outside the app. Download to replace it with the newest translation."
				EnglishText.Installed.None -> if( failed != null ) null else "The game's text is in Korean. Download the English translation (about 100 MB)."
			}
			status.text = when {
				gameDir == null -> "Choose a game folder first."
				state is EnglishText.State.Working -> state.message
				else -> listOfNotNull( failed, standing ).joinToString( " " )
			}
			refresh()
		}
		// installed from the app: whether the translation has moved since, asked of GitHub once per opening
		if( gameDir?.let { EnglishText.installed( it ) } is EnglishText.Installed.App )
			thread( name = "english-latest" ) {
				val result = runCatching { EnglishText.latest() }
				status.post {
					latest = result.fold( { Latest.Known( it ) }, { Latest.Unknown( it.message ?: it.javaClass.simpleName ) } )
					show( EnglishText.state )
				}
			}
		stopListening = EnglishText.listen { state ->
			status.post {
				// an install just finished: what it installed is the newest there is
				if( last is EnglishText.State.Working && state is EnglishText.State.Idle )
					( gameDir?.let { EnglishText.installed( it ) } as? EnglishText.Installed.App )?.let { latest = Latest.Known( it.commit ) }
				last = state
				show( state )
			}
		}
		row.line.addView( action, LinearLayout.LayoutParams( Ui.dp( context, 112f ), Ui.dp( context, 34f ) ) )
		return row
	}

	/** ‹ or ›: a round touch target (the controller steps with the D-pad on the row). */
	private fun arrow( label: String, about: String, onClick: () -> Unit ) = Ui.text( context, 20f, Ui.ACCENT ).apply {
		text = label
		gravity = Gravity.CENTER
		isClickable = true
		background = Ui.focusable( context, 0x14FFFFFF, 18f )
		setOnClickListener {
			help.text = about
			onClick()
		}
	}

	private fun square() = LinearLayout.LayoutParams( Ui.dp( context, 36f ), Ui.dp( context, 36f ) )

	private companion object {
		const val RESTART_NOTE = "Takes effect when you leave this screen: the server restarts with it. Your characters keep everything."
		const val ENGLISH_HELP = "ROenglishRE, llchrisll's English translation: its newest version, downloaded from GitHub " +
			"into your game folder's data/ (about 100 MB). Update lights up when the translation has changed since. It goes " +
			"on if you leave this screen; in use from your next game."
		const val SAVE_LOGS_HELP = "Copies the game's log, env.txt and the servers' logs to Download, a folder per save, " +
			"for a file manager to open or share. The game's log is the last game's: save it before you play again."
		const val TRACE_HELP = "The game's log gets a line a second of what the frames hand the GPU, a line for each " +
			"frame that loses every sprite, and the graphics driver's own messages. From your next game; turn it off " +
			"when you are done, as the log grows."
	}
}
