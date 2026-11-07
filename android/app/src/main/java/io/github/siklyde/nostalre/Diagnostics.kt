package io.github.siklyde.nostalre

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The game's and the servers' logs, copied where a file manager reaches them (the app's own files are not, without adb). */
object Diagnostics {
	/** What [saveLogs] copied: the folder, the files' names and the game's log size (bytes, null without one). */
	class Saved( val folder: File, val names: List<String>, val clientLog: Long? )

	/**
	 * Copies the game's log (client.log: the last game's, written over at each start) and the servers' logs (logs/) to
	 * Download/<app name> logs/<date-time>/.
	 */
	fun saveLogs( context: Context ): Saved {
		val clientLog = File( context.filesDir, "client.log" ).takeIf { it.isFile }
		val serverLogs = Paths( context ).logs.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
		val files = listOfNotNull( clientLog ) + serverLogs
		if( files.isEmpty() )
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
