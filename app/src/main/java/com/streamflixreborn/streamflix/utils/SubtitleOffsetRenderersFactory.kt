package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer

class SubtitleOffsetRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val renderer = TextRenderer(
            output,
            outputLooper,
            OffsetSubtitleDecoderFactory { UserPreferences.subtitleOffsetMs * 1000L },
        )
        renderer.experimentalSetLegacyDecodingEnabled(true)
        out.add(renderer)
    }
}

private class OffsetSubtitleDecoderFactory(
    private val offsetProviderUs: () -> Long,
) : SubtitleDecoderFactory {
    override fun supportsFormat(format: Format): Boolean =
        SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        OffsetSubtitleDecoder(SubtitleDecoderFactory.DEFAULT.createDecoder(format), offsetProviderUs)
}

private class OffsetSubtitleDecoder(
    private val delegate: SubtitleDecoder,
    private val offsetProviderUs: () -> Long,
) : SubtitleDecoder {
    override fun getName(): String = delegate.name

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        delegate.setOutputStartTimeUs((outputStartTimeUs - offsetProviderUs()).coerceAtLeast(0L))
    }

    override fun dequeueInputBuffer(): SubtitleInputBuffer? = delegate.dequeueInputBuffer()

    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) {
        delegate.queueInputBuffer(inputBuffer)
    }

    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? =
        delegate.dequeueOutputBuffer()?.let { OffsetSubtitleOutputBuffer(it, offsetProviderUs) }

    override fun flush() {
        delegate.flush()
    }

    override fun release() {
        delegate.release()
    }

    override fun setPositionUs(positionUs: Long) {
        delegate.setPositionUs((positionUs - offsetProviderUs()).coerceAtLeast(0L))
    }
}

private class OffsetSubtitleOutputBuffer(
    private val delegate: SubtitleOutputBuffer,
    private val offsetProviderUs: () -> Long,
) : SubtitleOutputBuffer() {
    init {
        timeUs = delegate.timeUs + offsetProviderUs()
        skippedOutputBufferCount = delegate.skippedOutputBufferCount
        shouldBeSkipped = delegate.shouldBeSkipped
        if (delegate.isEndOfStream) addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        if (delegate.isKeyFrame) addFlag(C.BUFFER_FLAG_KEY_FRAME)
        if (delegate.isFirstSample) addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
        if (delegate.isLastSample) addFlag(C.BUFFER_FLAG_LAST_SAMPLE)
        if (delegate.hasSupplementalData()) addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        if (delegate.notDependedOn()) addFlag(C.BUFFER_FLAG_NOT_DEPENDED_ON)
    }

    private val offsetUs: Long
        get() = offsetProviderUs()

    override fun getEventTimeCount(): Int = delegate.eventTimeCount

    override fun getEventTime(index: Int): Long = delegate.getEventTime(index) + offsetUs

    override fun getNextEventTimeIndex(timeUs: Long): Int =
        delegate.getNextEventTimeIndex(timeUs - offsetUs)

    override fun getCues(timeUs: Long) = delegate.getCues(timeUs - offsetUs)

    override fun release() {
        delegate.release()
    }
}