package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
	@Test fun theTraceAddsItsLinesAndKeepsTheOthers() {
		val on = Diagnostics.withTracing( listOf( "SOLO_FPS_LOG=1", "" ), on = true )
		assertEquals( "SOLO_FPS_LOG=1", on.first() )
		assertTrue( "NOSTALRE_FRAME_TRACE=1" in on )
		assertTrue( "WGPU_DEBUG=1" in on )
		assertTrue( on.any { it.startsWith( "RUST_LOG=" ) } )
		assertTrue( Diagnostics.tracing( on ) )
	}

	@Test fun turningItOffTakesItsKeysOutWhateverTheirValues() {
		val off = Diagnostics.withTracing( listOf( "RUST_LOG=debug", " WGPU_DEBUG = 0", "NOSTALRE_FRAME_TRACE=1", "SOLO_FPS_LOG=1" ), on = false )
		assertEquals( listOf( "SOLO_FPS_LOG=1" ), off )
		assertFalse( Diagnostics.tracing( off ) )
	}

	@Test fun turningItOnTwiceWritesEachKeyOnce() {
		val twice = Diagnostics.withTracing( Diagnostics.withTracing( emptyList(), on = true ), on = true )
		assertEquals( twice.size, twice.map { it.substringBefore( '=' ) }.toSet().size )
		assertTrue( Diagnostics.withTracing( emptyList(), on = false ).isEmpty() )
	}
}
