package io.github.siklyde.nostalre

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the game can tell about itself on a device without adb: its logs copied where a file manager reaches them, and
 * the graphics trace, lines of env.txt that the game reads at its start (the client's `android_main`): a line a second
 * of what the frames hand the GPU, a line for each frame that loses every world sprite, and the graphics driver's own
 * messages, all in client.log.
 */
object Diagnostics {
	/** The lines the trace adds to env.txt. Turning it on or off replaces these keys; any other line stays. */
	private val TRACE = linkedMapOf(
		"NOSTALRE_FRAME_TRACE" to "1",
		"WGPU_DEBUG" to "1",
		"RUST_LOG" to "info,wgpu_core=info,wgpu_hal=info,naga=warn,symphonia=warn",
	)

	private fun env( context: Context ) = File( context.filesDir, "env.txt" )

	private fun key( line: String ) = line.substringBefore( '=' ).trim()

	/** Whether env.txt's [lines] have the trace on. */
	fun tracing( lines: List<String> ): Boolean = lines.any { key( it ) == "NOSTALRE_FRAME_TRACE" }

	/** env.txt's [lines] with the trace on or off: its keys gone, then added back when [on]. */
	fun withTracing( lines: List<String>, on: Boolean ): List<String> =
		lines.filter { it.isNotBlank() && key( it ) !in TRACE.keys } +
			if( on ) TRACE.map { ( k, v ) -> "$k=$v" } else emptyList()

	fun tracing( context: Context ): Boolean = tracing( read( context ) )

	/** From the next game; no lines left removes env.txt. */
	fun setTracing( context: Context, on: Boolean ) {
		val lines = withTracing( read( context ), on )
		val file = env( context )
		if( lines.isEmpty() )
			file.delete()
		else
			file.writeText( lines.joinToString( "\n", postfix = "\n" ) )
	}

	private fun read( context: Context ): List<String> = env( context ).takeIf { it.isFile }?.readLines().orEmpty()

	/** What [saveLogs] copied: the folder, the files' names and the game's log size (bytes, null without one). */
	class Saved( val folder: File, val names: List<String>, val clientLog: Long? )

	/**
	 * Copies the game's log (client.log: the last game's, written over at each start), env.txt and the servers' logs
	 * (logs/) to Download/<app name> logs/<date-time>/. Not config.json: it holds the account.
	 */
	fun saveLogs( context: Context ): Saved {
		val clientLog = File( context.filesDir, "client.log" ).takeIf { it.isFile }
		val serverLogs = Paths( context ).logs.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
		val files = listOfNotNull( clientLog, env( context ).takeIf { it.isFile } ) + serverLogs
		if( clientLog == null && serverLogs.isEmpty() )
			throw IOException( "no logs yet: play a game first" )
		val label = context.applicationInfo.loadLabel( context.packageManager )
		val stamp = SimpleDateFormat( "yyyyMMdd-HHmmss", Locale.US ).format( Date() )
		val folder = File( Environment.getExternalStoragePublicDirectory( Environment.DIRECTORY_DOWNLOADS ), "$label logs/$stamp" )
		if( !folder.mkdirs() && !folder.isDirectory )
			throw IOException( "could not make ${folder.path}" )
		val copies = files.map { it.copyTo( File( folder, it.name ), overwrite = true ) }
		// written by path, the copies are not in the media index yet: file managers that read it, and a computer over
		// USB, would show the folder empty
		MediaScannerConnection.scanFile( context, copies.map { it.path }.toTypedArray(), null, null )
		return Saved( folder, copies.map { it.name }, clientLog?.length() )
	}
}
