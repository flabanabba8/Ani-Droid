package dev.anidroid

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.concurrent.atomic.AtomicLong

/** Shift cue timestamps before extraction/seek filtering, leaving audio/video untouched. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleTiming(private val offsetUs: AtomicLong,private val onParsed: () -> Unit = {}): SubtitleParser.Factory {
    private val defaults=DefaultSubtitleParserFactory()
    override fun supportsFormat(format: Format)=defaults.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format)=defaults.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser {
        val parser=defaults.create(format)
        val shift=offsetUs.get()
        return object: SubtitleParser by parser {
            override fun parse(data: ByteArray,offset: Int,length: Int,options: SubtitleParser.OutputOptions,output: Consumer<CuesWithTiming>) {
                // Shift whole cues before SubtitleExtractor filters/caches a seek.
                // Splitting at the unshifted seek boundary can replace an active cue
                // with its earlier fragment when playback starts from a saved position.
                // Long cues are emitted in one-second pieces: an in-buffer seek skips the
                // sample that began before the new position, so a piece lets the cue reappear
                // within a second instead of staying blank until the next cue.
                parser.parse(data,offset,length,SubtitleParser.OutputOptions.allCues(),Consumer { cues ->
                    val start=if(cues.startTimeUs==C.TIME_UNSET) cues.startTimeUs else cues.startTimeUs+shift
                    if(start==C.TIME_UNSET || cues.durationUs==C.TIME_UNSET || cues.durationUs<=PIECE_US) output.accept(CuesWithTiming(cues.cues,start,cues.durationUs))
                    else { var done=0L;while(done<cues.durationUs) {val piece=minOf(PIECE_US,cues.durationUs-done);output.accept(CuesWithTiming(cues.cues,start+done,piece));done+=piece} }
                })
                onParsed()
            }
        }
    }
    private companion object {const val PIECE_US=1_000_000L}
}
