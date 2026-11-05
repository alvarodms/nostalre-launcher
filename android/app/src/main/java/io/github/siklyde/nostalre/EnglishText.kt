package io.github.siklyde.nostalre

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The English text, set up from the app: ROenglishRE (llchrisll's English translation, which has no license, so the
 * APK cannot carry it) downloaded from GitHub at [COMMIT] and written into the game folder's data/ by
 * [EnglishImport], as scripts/import-english.py does on a PC. One run at a time, on its own thread, which goes on
 * when the screen that started it closes; [listen] follows it. The files wait in the cache until the run finishes, so
 * a run cut short picks up where it stopped.
 */
object EnglishText {
	const val REPO = "llchrisll/ROenglishRE"
	/** The translation's commit the app installs: moved by hand, once the import is checked against it. */
	const val COMMIT = "4a4abafd3fc6e360b57b41c297e9f9340fc66a26"
	/** In data/: the commit the app installed there. */
	private const val MARKER = ".nostalre-english"
	/** A table only the English import writes, the app's or the PC script's. */
	private const val IMPORTED = "rathenacardtable.txt"
	private const val THREADS = 6
	private const val ATTEMPTS = 3
	private const val CONNECT_TIMEOUT_MS = 15_000
	private const val READ_TIMEOUT_MS = 30_000
	private const val MB = 1_000_000L

	sealed class State {
		object Idle : State()
		data class Working( val message: String ) : State()
		data class Failed( val message: String ) : State()
	}

	enum class Installed { CURRENT, OLDER, OTHER, NONE }

	@Volatile var state: State = State.Idle
		private set

	/** Runs that finished: a screen compares it to know one finished while it was open. */
	@Volatile var finished = 0
		private set

	private val listeners = CopyOnWriteArrayList<( State ) -> Unit>()

	/** What the game folder's data/ holds: this app's [COMMIT], an older one, English the app did not write, none. */
	fun installed( gameDir: File ): Installed {
		val data = File( gameDir, "data" )
		val commit = runCatching { File( data, MARKER ).readText().trim() }.getOrNull()
		return when {
			commit == COMMIT -> Installed.CURRENT
			commit != null -> Installed.OLDER
			File( data, IMPORTED ).isFile -> Installed.OTHER
			else -> Installed.NONE
		}
	}

	/** [listener] now with the current state, then with each change, on the run's thread; the returned call stops it. */
	fun listen( listener: ( State ) -> Unit ): () -> Unit {
		listeners += listener
		listener( state )
		return { listeners -= listener }
	}

	/** Downloads and installs into [gameDir], unless a run is going. */
	@Synchronized
	fun start( context: Context, gameDir: File ) {
		if( state is State.Working )
			return
		update( State.Working( "Starting…" ) )
		val app = context.applicationContext
		thread( name = "english" ) {
			val result = runCatching { install( app, gameDir ) }
			if( result.isSuccess )
				finished++
			update( result.exceptionOrNull()?.let { State.Failed( it.message ?: it.javaClass.simpleName ) } ?: State.Idle )
		}
	}

	private fun install( context: Context, gameDir: File ) {
		val cache = File( context.cacheDir, "english" )
		cache.listFiles()?.filter { it.name != COMMIT }?.forEach { it.deleteRecursively() } // an older commit's
		val staging = File( cache, COMMIT )

		update( State.Working( "Listing the translation's files…" ) )
		val blobs = listing()
		val files = EnglishImport.files( blobs.keys )
		val total = files.sumOf { blobs.getValue( it ).size }
		if( context.cacheDir.usableSpace < total + 50 * MB || gameDir.usableSpace < total + 50 * MB )
			throw IOException( "not enough free space: it needs about ${2 * total / MB + 100} MB" )

		val done = AtomicLong()
		val shown = AtomicLong( -1 )
		fun progress( bytes: Long ) {
			val mb = done.addAndGet( bytes ) / MB
			if( shown.getAndSet( mb ) != mb )
				update( State.Working( "Downloading: $mb of ${total / MB} MB" ) )
		}
		progress( 0 )
		val pool = Executors.newFixedThreadPool( THREADS )
		try {
			files.map { path -> pool.submit { fetch( path, blobs.getValue( path ), File( staging, path ) ); progress( blobs.getValue( path ).size ) } }
				.forEach { it.get() }
		} catch( e: ExecutionException ) {
			throw e.cause ?: e
		} finally {
			pool.shutdownNow()
		}

		update( State.Working( "Writing the English text into ${gameDir.name}/data…" ) )
		val data = File( gameDir, "data" )
		EnglishImport.build( blobs.keys, staging, File( Paths( context ).server, "db/re" ), data )
		File( data, MARKER ).writeText( "$COMMIT\n" )
		cache.deleteRecursively()
	}

	private class Blob( val sha: String, val size: Long )

	/** Every file of the translation at [COMMIT]: one call to GitHub's API, of the 60 an hour it allows without an account. */
	private fun listing(): Map<String, Blob> {
		val json = JSONObject( String( get( "https://api.github.com/repos/$REPO/git/trees/$COMMIT?recursive=1" ), Charsets.UTF_8 ) )
		if( json.optBoolean( "truncated" ) )
			throw IOException( "GitHub sent part of the file list only" )
		val tree = json.getJSONArray( "tree" )
		return ( 0 until tree.length() ).map { tree.getJSONObject( it ) }
			.filter { it.getString( "type" ) == "blob" }
			.associate { it.getString( "path" ) to Blob( it.getString( "sha" ), it.getLong( "size" ) ) }
	}

	/** [path] into [dest], unless an earlier run left it there whole; checked against its git hash. */
	private fun fetch( path: String, blob: Blob, dest: File ) {
		if( dest.isFile && dest.length() == blob.size && gitHash( dest.readBytes() ) == blob.sha )
			return
		var failure: IOException? = null
		repeat( ATTEMPTS ) {
			try {
				val bytes = get( raw( path ) )
				if( gitHash( bytes ) != blob.sha )
					throw IOException( "$path came damaged" )
				dest.parentFile?.mkdirs()
				val part = File( dest.path + ".part" )
				part.writeBytes( bytes )
				if( !part.renameTo( dest ) )
					throw IOException( "could not write $dest" )
				return
			} catch( e: IOException ) {
				failure = e
			}
		}
		throw failure!!
	}

	private fun raw( path: String ) = "https://raw.githubusercontent.com/$REPO/$COMMIT/" + buildString {
		for( byte in path.toByteArray( Charsets.UTF_8 ) ) {
			val c = byte.toInt() and 0xff
			if( c < 0x80 && ( c.toChar().isLetterOrDigit() || c.toChar() in "-._~/" ) )
				append( c.toChar() )
			else
				append( '%' ).append( "%02X".format( c ) )
		}
	}

	private fun get( url: String ): ByteArray {
		val connection = ( URL( url ).openConnection() as HttpURLConnection ).apply {
			connectTimeout = CONNECT_TIMEOUT_MS
			readTimeout = READ_TIMEOUT_MS
			setRequestProperty( "User-Agent", "nostalRE-launcher" )
		}
		try {
			when( val code = connection.responseCode ) {
				200 -> return connection.inputStream.use { it.readBytes() }
				403, 429 -> throw IOException( "GitHub is limiting downloads from this network. Try again in an hour" )
				else -> throw IOException( "GitHub answered $code for $url" )
			}
		} catch( e: IOException ) {
			throw if( e.message?.startsWith( "GitHub" ) == true ) e else IOException( "no connection to GitHub (${e.message})", e )
		} finally {
			connection.disconnect()
		}
	}

	/** Git's hash of a file's contents: what the listing gives for each. */
	private fun gitHash( bytes: ByteArray ): String {
		val sha = MessageDigest.getInstance( "SHA-1" )
		sha.update( "blob ${bytes.size}\u0000".toByteArray( Charsets.US_ASCII ) )
		return sha.digest( bytes ).joinToString( "" ) { "%02x".format( it ) }
	}

	private fun update( new: State ) {
		state = new
		listeners.forEach { it( new ) }
	}
}
